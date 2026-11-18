package com.minime.agent.server

import com.minime.agent.server.modules.BrowserDriver
import com.minime.agent.server.modules.BrowserDriverRouter
import com.minime.agent.server.modules.BrowserLoginSignal
import com.minime.agent.server.modules.BrowserNavigation
import com.minime.agent.server.modules.BrowserTab
import com.minime.agent.server.modules.BrowserUnavailableException
import com.minime.agent.server.modules.LoginFrame
import com.minime.agent.server.modules.LoginInputEvent
import com.minime.agent.server.modules.LoginState
import com.minime.agent.server.modules.LoginWallDetector
import com.minime.agent.server.modules.ServerBrowserTool
import com.minime.code.feature.agent.domain.tool.ToolResult
import com.minime.agent.server.LoginStateResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** BrowserDriver 抽象层 + 登录挂起信号的契约测试（纯本地 fake，不发真实网络）。 */
class BrowserDriverTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** 可配置的假驱动：navigate 结果可控，其余返回空/成功。 */
    private class FakeDriver(
        private val nav: BrowserNavigation? = null,
        private val navThrows: Boolean = false,
        override val kind: String = "fake"
    ) : BrowserDriver {
        var startLoginCalled = false
        override fun isAvailable() = true
        override suspend fun navigate(url: String, tabId: String?, profileId: String): BrowserNavigation {
            if (navThrows) throw BrowserUnavailableException("fake down")
            return nav ?: BrowserNavigation(tabId ?: "tab-1", url, "t", "<html>ok</html>", false)
        }
        override suspend fun getText(tabId: String) = "text"
        override suspend fun getHtml(tabId: String) = "<html>ok</html>"
        override suspend fun listTabs(): List<BrowserTab> = listOf(BrowserTab("tab-1", "https://a", "t"))
        override suspend fun closeTab(tabId: String) = true
        override suspend fun startLogin(loginId: String, url: String, profileId: String): LoginState { startLoginCalled = true; return LoginState(loginId, "waiting", url) }
        override suspend fun loginFrame(loginId: String, after: Long) = LoginFrame(after + 1, "abc", 800, 600, true)
        override suspend fun loginInput(loginId: String, input: LoginInputEvent) = true
        override suspend fun loginStatus(loginId: String) = LoginState(loginId, "waiting")
        override suspend fun finishLogin(loginId: String) = true
    }

    @Test
    fun loginWallDetector_recognizesLoginPages() {
        assertTrue(LoginWallDetector.detect("https://x.com/login", "<html></html>") != null)
        assertTrue(LoginWallDetector.detect("https://x.com", "<form><input type=\"password\"></form><h1>Sign in</h1>") != null)
        assertEquals(null, LoginWallDetector.detect("https://x.com/docs", "<html><h1>Docs</h1></html>"))
    }

    @Test
    fun loginState_carriesDetailFor2FAIntermediate() {
        // 新增 detail 字段：2FA 中间态可被上层精确识别（与 state 一致）
        val st = LoginState("login-1", "authenticating", "https://x.com/2fa",
            "检测到二次验证 / 验证码，请在手机上完成", "authenticating")
        assertEquals("authenticating", st.state)
        assertEquals("authenticating", st.detail)
        assertTrue(st.message.contains("二次验证"))
    }

    @Test
    fun loginStateResponse_serializesDetail() {
        val resp = LoginStateResponse("login-1", "authenticating", "https://x.com/2fa", "2FA", "authenticating")
        val obj = json.parseToJsonElement(json.encodeToString(LoginStateResponse.serializer(), resp)).jsonObject
        assertEquals("authenticating", obj["detail"]?.jsonPrimitive?.content)
        assertEquals("authenticating", obj["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun loginSignal_roundTrip() {
        val signal = BrowserLoginSignal.create("login-1", "https://x.com/login", "需要登录")
        val info = BrowserLoginSignal.parse(signal)
        assertTrue(info != null)
        assertEquals("login-1", info!!.loginId)
        assertEquals("https://x.com/login", info.url)
        // 普通结果不会被误判为登录信号
        val normal = ToolResult.Success(json.parseToJsonElement("{\"ok\":true}"))
        assertEquals(null, BrowserLoginSignal.parse(normal))
    }

    @Test
    fun browserTool_emitsLoginSignalOnLoginWall() = runBlocking {
        val driver = FakeDriver(nav = BrowserNavigation("tab-1", "https://x.com/login", "t", "<html>login</html>", true))
        val tool = ServerBrowserTool(driver)
        val args = mapOf(
            "action" to json.parseToJsonElement("\"navigate\""),
            "url" to json.parseToJsonElement("\"https://x.com/login\"")
        )
        val result = tool.execute(args)
        val info = BrowserLoginSignal.parse(result)
        assertTrue(info != null)
        assertEquals("https://x.com/login", info!!.url)
    }

    @Test
    fun browserTool_normalNavigation_returnsEnvelope() = runBlocking {
        val driver = FakeDriver()
        val tool = ServerBrowserTool(driver)
        val result = tool.execute(mapOf(
            "action" to json.parseToJsonElement("\"navigate\""),
            "url" to json.parseToJsonElement("\"https://x.com/docs\"")
        ))
        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(true, data["ok"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("navigate", data["action"]?.jsonPrimitive?.content)
        assertEquals(null, BrowserLoginSignal.parse(result))
    }

    @Test
    fun router_fallsBackToLocalWhenRemoteDown() = runBlocking {
        val remote = FakeDriver(navThrows = true, kind = "remote")
        val local = FakeDriver(nav = BrowserNavigation("tab-x", "u", "local-title", "<b>local</b>", false), kind = "local")
        val router = BrowserDriverRouter(remote, local)
        // FakeDriver.isAvailable 恒 true，但 navigate 抛 BrowserUnavailableException → 触发降级
        val nav = router.navigate("https://x.com")
        assertEquals("local-title", nav.title)
    }

    @Test
    fun loginDeferred_registerResolve_unblocks() = runBlocking {
        val store = EventStore(java.io.File(createTempDir(), "login-test.db").absolutePath)
        val d: CompletableDeferred<Boolean> = store.registerPending("login:abc", "s1", "t1", "login", "https://x.com")
        assertFalse(d.isCompleted)
        assertTrue(store.resolvePending("login:abc", true))
        assertTrue(d.await())
        // 重复 resolve 返回 false（幂等）
        assertFalse(store.resolvePending("login:abc", false))
        store.close()
    }
}
