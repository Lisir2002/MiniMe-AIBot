package com.minime.agent.server.modules

import com.minime.code.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 浏览器驱动抽象层：把"服务端如何驱动一个浏览器"与"工具/挂起逻辑"解耦。
 *
 * 这是"吸纳方案1优点"的核心——ServerBrowserTool 只依赖本接口，不关心底层是
 * 独立微服务（RemoteBrowserDriver，方案2）还是未来内嵌的 Playwright JVM（方案1）。
 * 加一个新实现即可切换，上层工具、挂起逻辑、App 面板一行不改。
 *
 * 三种实现：
 * - [RemoteBrowserDriver]：HTTP 调 Python Playwright 微服务，支持真实登录会话。
 * - [LocalBrowserDriver]：无头 HttpURLConnection 只读兜底，微服务离线时降级。
 * - [BrowserDriverRouter]：组合二者，读操作优先远程、失败降级本地；登录会话必须远程。
 */

// ---------- 数据契约（Kotlin 侧权威定义，Python 微服务同构对齐）----------

data class BrowserNavigation(
    val tabId: String,
    val url: String,
    val title: String,
    val html: String,
    val needsLogin: Boolean
)

data class BrowserTab(val tabId: String, val url: String, val title: String)

/** App 登录面板轮询的一帧：变化检测增量帧（静态页不重复推）。 */
data class LoginFrame(
    val seq: Long,
    val jpegBase64: String,
    val width: Int,
    val height: Int,
    val changed: Boolean
)

data class LoginInputEvent(
    val type: String,   // click / text / key / scroll
    val x: Int = 0,
    val y: Int = 0,
    val text: String = "",
    val key: String = "",
    val dx: Int = 0,
    val dy: Int = 0
)

data class LoginState(
    val loginId: String,
    // waiting / login / authenticating(2FA 中间态) / success / failed / timeout
    val state: String,
    val url: String = "",
    val message: String = "",
    val detail: String = ""   // 机器可读状态，供上层精确识别中间态
)

/** 浏览器驱动不可用（微服务离线/未配置）——供上层熔断降级，不被当成普通网络错误吞掉。 */
class BrowserUnavailableException(message: String) : RuntimeException(message)

interface BrowserDriver {
    val kind: String
    fun isAvailable(): Boolean

    // ---- 只读网页能力（browser 工具）----
    suspend fun navigate(url: String, tabId: String? = null, profileId: String = "default"): BrowserNavigation
    suspend fun getText(tabId: String): String
    suspend fun getHtml(tabId: String): String
    suspend fun listTabs(): List<BrowserTab>
    suspend fun closeTab(tabId: String): Boolean

    // ---- 登录会话能力（LOGIN_REQUIRED 挂起）----
    suspend fun startLogin(loginId: String, url: String, profileId: String): LoginState
    suspend fun loginFrame(loginId: String, after: Long): LoginFrame
    suspend fun loginInput(loginId: String, input: LoginInputEvent): Boolean
    suspend fun loginStatus(loginId: String): LoginState
    suspend fun finishLogin(loginId: String): Boolean
}

// ---------- 登录墙检测（纯逻辑，两端共用）----------

object LoginWallDetector {
    private val LOGIN_PATHS = listOf("/login", "/signin", "/sign-in", "/auth/login", "/user/login", "/account/login")
    private val LOGIN_WORDS = listOf("sign in", "log in", "登录", "登陆", "password", "验证码")

    /** @return 命中登录墙的原因；null 表示看起来是正常页面。 */
    fun detect(url: String, html: String): String? {
        val u = url.lowercase()
        val h = html.lowercase()
        if (LOGIN_PATHS.any { u.contains(it) }) return "URL 指向登录页"
        val hasPassword = h.contains("type=\"password\"") || h.contains("type='password'")
        if (hasPassword && LOGIN_WORDS.any { h.contains(it) }) return "页面检测到登录表单"
        if (h.contains("captcha") && LOGIN_WORDS.any { h.contains(it) }) return "页面要求验证码/登录"
        return null
    }
}

// ---------- 工具 <-> AgentRunner 的登录挂起信号 ----------

object BrowserLoginSignal {
    private const val KEY = "__login_required"

    /** 工具检测到登录墙时返回的特殊 Success.data，AgentRunner 识别后挂起并发 LOGIN_REQUIRED 事件。 */
    fun create(loginId: String, url: String, reason: String): ToolResult.Success =
        ToolResult.Success(buildJsonObject {
            put(KEY, buildJsonObject {
                put("loginId", JsonPrimitive(loginId))
                put("url", JsonPrimitive(url))
                put("reason", JsonPrimitive(reason))
            })
        })

    fun parse(result: ToolResult): LoginInfo? {
        if (result !is ToolResult.Success) return null
        val obj = result.data as? JsonObject ?: return null
        val inner = obj[KEY] as? JsonObject ?: return null
        return LoginInfo(
            loginId = inner["loginId"]?.jsonPrimitive?.content ?: return null,
            url = inner["url"]?.jsonPrimitive?.content ?: "",
            reason = inner["reason"]?.jsonPrimitive?.content ?: ""
        )
    }
}

data class LoginInfo(val loginId: String, val url: String, val reason: String)

// ---------- 本地降级驱动（只读，无登录能力）----------

/** 微服务离线时的兜底：用 HttpURLConnection 只读抓取，登录会话能力不可用。 */
class LocalBrowserDriver : BrowserDriver {
    override val kind = "local"
    private data class Tab(val id: String, val url: String, val html: String, val title: String)
    private val tabs = ConcurrentHashMap<String, Tab>()
    private var seq = 0

    override fun isAvailable(): Boolean = true  // 本地兜底永远可用

    override suspend fun navigate(url: String, tabId: String?, profileId: String): BrowserNavigation = withContext(Dispatchers.IO) {
        val id = tabId ?: "tab-${++seq}"
        val fetched = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "MiniMe-CloudAgent-Browser/1.0")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(200_000) }
        } catch (e: Exception) {
            throw BrowserUnavailableException("导航失败: ${e.message}")
        }
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
            .find(fetched)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        tabs[id] = Tab(id, url, fetched, title)
        BrowserNavigation(id, url, title, fetched, LoginWallDetector.detect(url, fetched) != null)
    }

    override suspend fun getText(tabId: String): String = htmlOf(tabId)
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(100_000)

    override suspend fun getHtml(tabId: String): String = htmlOf(tabId)

    private fun htmlOf(tabId: String): String =
        tabs[tabId]?.html ?: throw BrowserUnavailableException("未知标签: $tabId")

    override suspend fun listTabs(): List<BrowserTab> =
        tabs.values.map { BrowserTab(it.id, it.url, it.title) }

    override suspend fun closeTab(tabId: String): Boolean = tabs.remove(tabId) != null

    override suspend fun startLogin(loginId: String, url: String, profileId: String): LoginState =
        throw BrowserUnavailableException("本地降级驱动不支持登录会话，需启用浏览器微服务")

    override suspend fun loginFrame(loginId: String, after: Long): LoginFrame =
        throw BrowserUnavailableException("本地降级驱动不支持登录会话")

    override suspend fun loginInput(loginId: String, input: LoginInputEvent): Boolean =
        throw BrowserUnavailableException("本地降级驱动不支持登录会话")

    override suspend fun loginStatus(loginId: String): LoginState =
        throw BrowserUnavailableException("本地降级驱动不支持登录会话")

    override suspend fun finishLogin(loginId: String): Boolean =
        throw BrowserUnavailableException("本地降级驱动不支持登录会话")
}

// ---------- 远程驱动（HTTP 调 Python Playwright 微服务）----------

/** 通过 HTTP 驱动独立浏览器微服务。契约与 Python 侧 navigate 及 login 系列端点同构。 */
class RemoteBrowserDriver(
    private val baseUrl: String,
    private val healthTtlMs: Long = 10_000L,
    private val token: String = System.getenv("MINIME_BROWSER_TOKEN") ?: ""
) : BrowserDriver {
    override val kind = "remote"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastHealthAt = 0L
    @Volatile
    private var lastHealthOk = false

    override fun isAvailable(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHealthAt < healthTtlMs) return lastHealthOk
        return try {
            val req = Request.Builder().url("$baseUrl/health").build()
            client.newCall(req).execute().use { lastHealthOk = it.isSuccessful }
            lastHealthAt = System.currentTimeMillis()
            lastHealthOk
        } catch (e: Exception) {
            lastHealthOk = false
            lastHealthAt = System.currentTimeMillis()
            false
        }
    }

    override suspend fun navigate(url: String, tabId: String?, profileId: String): BrowserNavigation = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("url", JsonPrimitive(url))
            tabId?.let { put("tabId", JsonPrimitive(it)) }
            put("profileId", JsonPrimitive(profileId))
        }.toString()
        val o = post("/navigate", body)
        BrowserNavigation(
            tabId = o["tabId"]?.jsonPrimitive?.content ?: "",
            url = o["url"]?.jsonPrimitive?.content ?: url,
            title = o["title"]?.jsonPrimitive?.content ?: "",
            html = o["html"]?.jsonPrimitive?.content ?: "",
            needsLogin = o["needsLogin"]?.jsonPrimitive?.content?.toBoolean() ?: false
        )
    }

    override suspend fun getText(tabId: String): String = withContext(Dispatchers.IO) {
        get("/text?tabId=$tabId")["text"]?.jsonPrimitive?.content ?: ""
    }

    override suspend fun getHtml(tabId: String): String = withContext(Dispatchers.IO) {
        get("/html?tabId=$tabId")["html"]?.jsonPrimitive?.content ?: ""
    }

    override suspend fun listTabs(): List<BrowserTab> = withContext(Dispatchers.IO) {
        val arr = get("/tabs")["tabs"]
        if (arr == null || !arr.toString().startsWith("[")) emptyList()
        else arr.jsonArray.mapNotNull { it as? JsonObject }.map {
            BrowserTab(
                it["tabId"]?.jsonPrimitive?.content ?: "",
                it["url"]?.jsonPrimitive?.content ?: "",
                it["title"]?.jsonPrimitive?.content ?: ""
            )
        }
    }

    override suspend fun closeTab(tabId: String): Boolean = withContext(Dispatchers.IO) {
        post("/closeTab", buildJsonObject { put("tabId", JsonPrimitive(tabId)) }.toString())["ok"]?.jsonPrimitive?.content?.toBoolean() ?: false
    }

    override suspend fun startLogin(loginId: String, url: String, profileId: String): LoginState = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("loginId", JsonPrimitive(loginId))
            put("url", JsonPrimitive(url))
            put("profileId", JsonPrimitive(profileId))
        }.toString()
        val o = post("/login/start", body)
        stateOf(loginId, o)
    }

    override suspend fun loginFrame(loginId: String, after: Long): LoginFrame = withContext(Dispatchers.IO) {
        val o = get("/login/$loginId/frame?after=$after")
        LoginFrame(
            seq = o["seq"]?.jsonPrimitive?.content?.toLongOrNull() ?: after,
            jpegBase64 = o["jpegBase64"]?.jsonPrimitive?.content ?: "",
            width = o["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            height = o["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            changed = o["changed"]?.jsonPrimitive?.content?.toBoolean() ?: false
        )
    }

    override suspend fun loginInput(loginId: String, input: LoginInputEvent): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("type", JsonPrimitive(input.type))
            put("x", JsonPrimitive(input.x)); put("y", JsonPrimitive(input.y))
            put("text", JsonPrimitive(input.text)); put("key", JsonPrimitive(input.key))
            put("dx", JsonPrimitive(input.dx)); put("dy", JsonPrimitive(input.dy))
        }.toString()
        post("/login/$loginId/input", body)["ok"]?.jsonPrimitive?.content?.toBoolean() ?: false
    }

    override suspend fun loginStatus(loginId: String): LoginState = withContext(Dispatchers.IO) {
        stateOf(loginId, get("/login/$loginId/status"))
    }

    override suspend fun finishLogin(loginId: String): Boolean = withContext(Dispatchers.IO) {
        post("/login/$loginId/finish", "{}")["ok"]?.jsonPrimitive?.content?.toBoolean() ?: false
    }

    private fun stateOf(loginId: String, o: JsonObject) = LoginState(
        loginId = o["loginId"]?.jsonPrimitive?.content ?: loginId,
        state = o["state"]?.jsonPrimitive?.content ?: "waiting",
        url = o["url"]?.jsonPrimitive?.content ?: "",
        message = o["message"]?.jsonPrimitive?.content ?: "",
        detail = o["detail"]?.jsonPrimitive?.content ?: ""
    )

    private fun Request.Builder.withAuth(): Request.Builder {
        if (token.isNotBlank()) this.header("X-Browser-Token", token)
        return this
    }

    private fun get(path: String): JsonObject {
        val req = Request.Builder().url("$baseUrl$path").withAuth().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) throw BrowserUnavailableException("浏览器服务鉴权失败 ${resp.code}")
            if (!resp.isSuccessful) throw BrowserUnavailableException("浏览器服务 $path 返回 ${resp.code}: ${text.take(200)}")
            return runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw BrowserUnavailableException("浏览器服务响应非法 JSON: ${text.take(200)}") }
        }
    }

    private fun post(path: String, body: String): JsonObject {
        val req = Request.Builder().url("$baseUrl$path").withAuth()
            .post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) throw BrowserUnavailableException("浏览器服务鉴权失败 ${resp.code}")
            if (!resp.isSuccessful) throw BrowserUnavailableException("浏览器服务 $path 返回 ${resp.code}: ${text.take(200)}")
            return runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw BrowserUnavailableException("浏览器服务响应非法 JSON: ${text.take(200)}") }
        }
    }
}

/**
 * 组合路由：读操作优先走远程微服务，离线/异常时自动降级到本地只读驱动（熔断）；
 * 登录会话能力只在远程驱动上成立——本地无 WebView，强行降级无意义，直接抛出明确错误。
 *
 * 这就是方案1的可插拔点：未来把 remote 换成内嵌 Playwright JVM 实现，只需替换构造参数。
 */
class BrowserDriverRouter(
    private val remote: BrowserDriver,
    private val local: BrowserDriver = LocalBrowserDriver()
) : BrowserDriver {
    override val kind = "router(${remote.kind}+${local.kind})"

    @Volatile
    private var degraded = false

    override fun isAvailable(): Boolean = remote.isAvailable() || local.isAvailable()

    fun usingRemote(): Boolean = !degraded && remote.isAvailable()

    private suspend fun <T> read(remoteCall: suspend () -> T, localCall: suspend () -> T): T {
        if (!degraded && remote.isAvailable()) {
            try { return remoteCall() }
            catch (e: BrowserUnavailableException) { degraded = true }
        }
        return localCall()
    }

    override suspend fun navigate(url: String, tabId: String?, profileId: String): BrowserNavigation =
        read({ remote.navigate(url, tabId, profileId) }, { local.navigate(url, tabId, profileId) })

    override suspend fun getText(tabId: String): String = read({ remote.getText(tabId) }, { local.getText(tabId) })
    override suspend fun getHtml(tabId: String): String = read({ remote.getHtml(tabId) }, { local.getHtml(tabId) })
    override suspend fun listTabs(): List<BrowserTab> = read({ remote.listTabs() }, { local.listTabs() })
    override suspend fun closeTab(tabId: String): Boolean = read({ remote.closeTab(tabId) }, { local.closeTab(tabId) })

    // 登录会话：必须远程，永不降级到本地（本地驱动会抛明确的 BrowserUnavailableException）
    override suspend fun startLogin(loginId: String, url: String, profileId: String): LoginState = remote.startLogin(loginId, url, profileId)
    override suspend fun loginFrame(loginId: String, after: Long): LoginFrame = remote.loginFrame(loginId, after)
    override suspend fun loginInput(loginId: String, input: LoginInputEvent): Boolean = remote.loginInput(loginId, input)
    override suspend fun loginStatus(loginId: String): LoginState = remote.loginStatus(loginId)
    override suspend fun finishLogin(loginId: String): Boolean = remote.finishLogin(loginId)
}

/** 登录会话 ID 生成器。 */
fun newLoginId(): String = "login-${UUID.randomUUID()}"
