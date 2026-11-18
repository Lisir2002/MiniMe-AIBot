package com.minime.code.feature.agent.data.remote.cloudagent

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云端 agent-server 的 HTTP 客户端。服务器地址动态传入（用户设置里配置），
 * 因此用 OkHttp 手搓请求而非 Retrofit 静态 baseUrl。DTO 用 Gson 序列化，
 * 字段与 agent-server/Models.kt 一一对应。
 */
@Singleton
class CloudAgentApi @Inject constructor() {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // 轮询等待，不设读上限
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Suppress("UNCHECKED_CAST")
    suspend fun health(baseUrl: String): Map<String, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/health").get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            gson.fromJson(body, Map::class.java) as? Map<String, String> ?: emptyMap()
        }
    }

    suspend fun createSession(baseUrl: String, auth: String, req: CreateSessionReq): CreateSessionResp =
        post(baseUrl, auth, "/sessions", req, CreateSessionResp::class.java)

    suspend fun submitMessage(baseUrl: String, auth: String, sessionId: String, req: SubmitMessageReq): SubmitMessageResp =
        post(baseUrl, auth, "/sessions/$sessionId/messages", req, SubmitMessageResp::class.java)

    suspend fun events(baseUrl: String, auth: String, sessionId: String, after: Long): EventsResp =
        get(baseUrl, auth, "/sessions/$sessionId/events?after=$after", EventsResp::class.java)

    suspend fun decide(baseUrl: String, auth: String, decisionId: String, req: DecisionReq): OkResp =
        post(baseUrl, auth, "/decisions/$decisionId", req, OkResp::class.java)

    /** 主动停止云端任务：中断服务端的 LLM 调用并落「已停止」事件。[ok]=false 表示任务已结束。 */
    suspend fun cancelTask(baseUrl: String, auth: String, taskId: String): OkResp =
        post(baseUrl, auth, "/tasks/$taskId/cancel", CancelTaskReq(), OkResp::class.java)

    // ---- 环境数据（侧边栏文件/skill/授权按执行目标切换）--------------------------------
    suspend fun listWorkspace(baseUrl: String, auth: String, path: String): WorkspaceListResp =
        get(baseUrl, auth, "/workspace/list?path=${java.net.URLEncoder.encode(path, "UTF-8")}", WorkspaceListResp::class.java)

    suspend fun readWorkspace(baseUrl: String, auth: String, path: String): WorkspaceReadResp =
        get(baseUrl, auth, "/workspace/read?path=${java.net.URLEncoder.encode(path, "UTF-8")}", WorkspaceReadResp::class.java)

    suspend fun listSkills(baseUrl: String, auth: String): CloudSkillsResp =
        get(baseUrl, auth, "/skills", CloudSkillsResp::class.java)

    suspend fun policy(baseUrl: String, auth: String): CloudPolicyResp =
        get(baseUrl, auth, "/policy", CloudPolicyResp::class.java)

    // ---- 云端登录会话：手机遥控云端浏览器完成登录，全程只传画面与输入，不搬运凭证 ------------
    /** 拉取登录会话的增量帧。[after] 为上一帧序号；静态页 changed=false 时 App 不必重绘。 */
    suspend fun loginFrame(baseUrl: String, auth: String, loginId: String, after: Long): LoginFrameResp =
        get(baseUrl, auth, "/login/$loginId/frame?after=$after", LoginFrameResp::class.java)

    /** 把手机的触摸/键盘事件注入云端浏览器。 */
    suspend fun loginInput(baseUrl: String, auth: String, loginId: String, req: LoginInputReq): OkResp =
        post(baseUrl, auth, "/login/$loginId/input", req, OkResp::class.java)

    /** 查询登录会话状态（waiting/logging/success/failed/timeout）。 */
    suspend fun loginStatus(baseUrl: String, auth: String, loginId: String): LoginStateResp =
        get(baseUrl, auth, "/login/$loginId/status", LoginStateResp::class.java)

    /** 通知服务端登录完成/取消，唤醒挂起的任务。[success]=false 表示用户主动取消。 */
    suspend fun loginComplete(baseUrl: String, auth: String, loginId: String, success: Boolean): OkResp =
        post(baseUrl, auth, "/login/$loginId/complete", LoginCompleteReq(success), OkResp::class.java)

    /**
     * 订阅云端登录帧推流（WebSocket）。服务端主动推送变化帧与状态，替代原 350ms 轮询，更跟手。
     * - URL 由 http(s) 归一为 ws(s)，token 走 Authorization header（与 HTTP 端点一致）。
     * - 建连失败/被服务端关闭时 Flow 正常结束；上层据此降级到 HTTP 轮询，保证不断流。
     * - 消息用 Gson 解析 type 字段分发：frame → [LoginStreamMsg.Frame]，status → [LoginStreamMsg.Status]。
     */
    fun loginStream(baseUrl: String, auth: String, loginId: String): Flow<LoginStreamMsg> = callbackFlow {
        val wsUrl = baseUrl.replaceFirst(Regex("^http"), "ws") + "/login/$loginId/stream"
        val request = Request.Builder().url(wsUrl).header("Authorization", auth).build()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val o = gson.fromJson(text, JsonObject::class.java)
                    when (o.get("type")?.asString) {
                        "frame" -> trySend(
                            LoginStreamMsg.Frame(
                                seq = o.get("seq")?.asLong ?: 0L,
                                jpegBase64 = o.get("jpegBase64")?.asString.orEmpty(),
                                width = o.get("width")?.asInt ?: 0,
                                height = o.get("height")?.asInt ?: 0,
                                changed = o.get("changed")?.asBoolean ?: false
                            )
                        )
                        "status" -> trySend(
                            LoginStreamMsg.Status(
                                state = o.get("state")?.asString.orEmpty(),
                                message = o.get("message")?.asString.orEmpty(),
                                detail = o.get("detail")?.asString.orEmpty(),
                                url = o.get("url")?.asString.orEmpty()
                            )
                        )
                    }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
        }
        val ws = client.newWebSocket(request, listener)
        awaitClose { runCatching { ws.close(1000, "app-closed") } }
    }

    private suspend fun <T> post(baseUrl: String, auth: String, path: String, body: Any, type: Class<T>): T =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl$path")
                .header("Authorization", auth)
                .post(gson.toJson(body).toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw CloudAgentHttpException(resp.code, text)
                gson.fromJson(text, type)
            }
        }

    private suspend fun <T> get(baseUrl: String, auth: String, path: String, type: Class<T>): T =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$baseUrl$path").header("Authorization", auth).get().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw CloudAgentHttpException(resp.code, text)
                gson.fromJson(text, type)
            }
        }
}

class CloudAgentHttpException(val code: Int, val body: String) : Exception("云端服务 HTTP $code: ${body.take(200)}")

data class CreateSessionReq(val title: String = "新会话")
data class CreateSessionResp(val sessionId: String)

data class SubmitMessageReq(
    val content: String,
    val provider: ProviderConfigDto,
    /** 工具审批信任档位（full/basic/strict），随任务提交供云端异步审批裁决。 */
    val trustLevel: String = "basic"
)

data class ProviderConfigDto(
    val type: String = "anthropic",
    val apiKey: String,
    val baseUrl: String,
    val useFullUrl: Boolean = false,
    val model: String = "claude-3-5-sonnet-20241022"
)

data class SubmitMessageResp(val taskId: String, val accepted: Boolean = true)

data class EventsResp(
    val events: List<StoredEventDto>,
    val nextSeq: Long,
    val taskStatus: String = "running",
    val pendingDecisions: List<PendingDecisionDto> = emptyList(),
    /** 仍挂起等待用户登录的云端浏览器会话（断线重开据此恢复登录面板）。 */
    val pendingLogins: List<PendingLoginDto> = emptyList()
)

/** 仍挂起、等待用户在手机云端浏览器登录的会话（App 断线/重开后据此恢复登录面板）。 */
data class PendingLoginDto(
    val loginId: String,
    val url: String = "",
    val reason: String = "",
    val taskId: String = "",
    val sessionId: String = ""
)

/** 云端仍挂起、等待用户审批的工具调用（App 断线/重开后据此恢复审批卡片）。 */
data class PendingDecisionDto(
    val decisionId: String,
    val taskId: String = "",
    val toolName: String = "",
    val argsPreview: String = "",
    val sessionId: String = ""
)

data class StoredEventDto(
    val seq: Long,
    val sessionId: String,
    val kind: String,
    val taskId: String = "",
    val text: String = "",
    val toolName: String = "",
    val toolArgs: String = "",
    val toolCallId: String = "",
    val isError: Boolean = false,
    val reasoning: String = "",
    val thinkingBlocksJson: String = "",
    val signature: String = "",
    val timestamp: Long = 0L
) {
    /**
     * 确定性 messageId：前台实时流与「重开/回前台补拉」落库必须共用同一个 id，
     * 否则 Room 的 REPLACE 无法幂等，补拉会给同一条助手回复插入重复消息。
     * seq 是服务端全局自增主键，稳定且唯一。
     */
    val assistantMessageId: String get() = "cloud_a_$seq"
    val toolMessageId: String get() = "tool_${toolCallId.ifEmpty { "seq_$seq" }}"
}

data class DecisionReq(val approve: Boolean, val remember: Boolean = false)
data class CancelTaskReq(val reason: String = "user_stop")
data class OkResp(val ok: Boolean = true)

// ---- 云端登录会话 DTO -------------------------------------------------------

/** 增量帧：changed=false 表示画面未变，App 不必重绘。 */
data class LoginFrameResp(
    val seq: Long = 0,
    val jpegBase64: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val changed: Boolean = false
)

/** 登录会话状态。 */
data class LoginStateResp(
    val loginId: String = "",
    // waiting / login / authenticating(2FA 中间态) / success / failed / timeout
    val state: String = "waiting",
    val url: String = "",
    val message: String = "",
    val detail: String = ""          // 机器可读状态，供面板精确识别中间态
)

/** 手机触摸/键盘事件 → 云端浏览器输入。type: click / text / key / scroll。 */
data class LoginInputReq(
    val type: String,
    val x: Int = 0,
    val y: Int = 0,
    val text: String = "",
    val key: String = "",
    val dx: Int = 0,
    val dy: Int = 0
)

data class LoginCompleteReq(val success: Boolean = true)

/** WebSocket 登录推流的统一消息：服务端把增量帧与状态合并到一条连接推送。 */
sealed class LoginStreamMsg {
    data class Frame(
        val seq: Long = 0,
        val jpegBase64: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val changed: Boolean = false
    ) : LoginStreamMsg()
    data class Status(
        val state: String = "",
        val message: String = "",
        val detail: String = "",
        val url: String = ""
    ) : LoginStreamMsg()
}

// ---- 环境数据 DTO ----------------------------------------------------------

data class CloudWorkspaceFileEntry(
    val name: String = "",
    val isDirectory: Boolean = false,
    val size: Long = 0,
    val lastModified: Long = 0,
    val permissions: String = "---"
)

data class WorkspaceListResp(
    val path: String = "",
    val entries: List<CloudWorkspaceFileEntry> = emptyList(),
    val error: String = ""
)

data class WorkspaceReadResp(
    val path: String = "",
    val content: String = "",
    val error: String = ""
)

data class CloudSkillEntry(
    val name: String = "",
    val description: String = "",
    val requiredTools: List<String> = emptyList(),
    val dirPath: String = ""
)

data class CloudSkillsResp(val skills: List<CloudSkillEntry> = emptyList())

data class CloudToolPolicyEntry(val name: String = "", val permissionPolicy: String = "ASK")

data class CloudPolicyResp(
    val trustLevels: List<String> = emptyList(),
    val tools: List<CloudToolPolicyEntry> = emptyList(),
    val protocol: Int = 0
)
