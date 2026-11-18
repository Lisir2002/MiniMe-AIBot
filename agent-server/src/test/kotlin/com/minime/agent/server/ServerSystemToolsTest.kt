package com.minime.agent.server

import com.minime.agent.server.modules.FirewallManager
import com.minime.agent.server.modules.ProcessLifecycleManager
import com.minime.agent.server.modules.ServerFirewallTool
import com.minime.agent.server.modules.ServerProcessTool
import com.minime.code.feature.agent.domain.tool.ToolResult
import com.minime.code.feature.agent.domain.tool.toTransportString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 系统级能力测试：进程 TTL 分级、生命周期登记/回收/持久化，防火墙策略持久化与能力降级。
 * 不依赖真实 iptables / 长 sleep，聚焦纯逻辑。
 */
class ServerSystemToolsTest {
    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "minime-system-test-${System.nanoTime()}").also { it.mkdirs() }

    private fun js(json: String) = Json.parseToJsonElement(json)

    // ---------- 进程生命周期 ----------

    @Test
    fun `TTL分级 编译长服务更长普通短`() {
        assertEquals(ProcessLifecycleManager.TTL_BUILD_MS, ProcessLifecycleManager.classifyTtl("./gradlew assembleDebug"))
        assertEquals(ProcessLifecycleManager.TTL_BUILD_MS, ProcessLifecycleManager.classifyTtl("npm run build"))
        assertEquals(ProcessLifecycleManager.TTL_SERVICE_MS, ProcessLifecycleManager.classifyTtl("python3 -m http.server --serve"))
        assertEquals(ProcessLifecycleManager.TTL_SHORT_MS, ProcessLifecycleManager.classifyTtl("sleep 3600"))
        // 编译类必须远大于普通类，避免"编译到一半被回收"的反面作用
        assertTrue(ProcessLifecycleManager.TTL_BUILD_MS > ProcessLifecycleManager.TTL_SHORT_MS * 3)
    }

    @Test
    fun `进程登记心跳持久化与手动终止`() {
        val mgr = ProcessLifecycleManager()
        // pid 用一个不存在的大数，仅验证登记/标记逻辑，不实际 kill
        mgr.register("t1", 999999L, "./gradlew build", kind = ProcessLifecycleManager.KIND_TERMINAL)
        assertEquals(1, mgr.list().size)
        assertTrue(mgr.setPersistent("t1", true))
        assertTrue(mgr.get("t1")!!.persistent)
        assertTrue(mgr.setTtl("t1", 120 * 60_000L)) // setTtl 接收毫秒
        assertEquals(120 * 60_000L, mgr.get("t1")!!.ttlMs)
        assertTrue(mgr.setPersistent("t1", false))
        assertFalse(mgr.get("t1")!!.persistent)
        // 不存在进程 kill 返回 NotFound
        assertTrue(mgr.kill("nope") is ProcessLifecycleManager.KillOutcome.NotFound)
        mgr.unregister("t1")
        assertTrue(mgr.list().isEmpty())
    }

    @Test
    fun `process工具list与status结构合法`() = runBlocking {
        val mgr = ProcessLifecycleManager()
        val tool = ServerProcessTool(mgr)
        val listed = tool.execute(mapOf("action" to js("\"list\"")))
        assertTrue(listed is ToolResult.Success)
        val outer = Json.parseToJsonElement(listed.toTransportString()).jsonObject
        assertTrue(outer.containsKey("status"))
        val status = tool.execute(mapOf("action" to js("\"status\"")))
        assertTrue(status is ToolResult.Success)
        // persist 不存在的进程返回 Error
        assertTrue(tool.execute(mapOf("action" to js("\"persist\""), "id" to js("\"nope\""))) is ToolResult.Error)
    }

    // ---------- 防火墙 ----------

    @Test
    fun `防火墙策略持久化与白名单增删`() {
        val home = tempHome()
        val fw = FirewallManager(home.absolutePath)
        // 无 NET_ADMIN 环境下 enable 不假装成功
        fw.addRule("allow", FirewallManager.Rule(host = "api.example.com", port = 443))
        val state = fw.status()
        assertEquals(1, state.allow.size)
        assertEquals("api.example.com", state.allow[0].host)
        // 新实例从磁盘重读
        val reloaded = FirewallManager(home.absolutePath)
        assertEquals(1, reloaded.status().allow.size)
        // 移除
        val afterRemove = fw.removeRule("allow", FirewallManager.Rule(host = "api.example.com", port = 443))
        assertTrue(afterRemove.allow.isEmpty())
        assertTrue(File(home, ".minimecode/firewall/state.json").isFile)
    }

    @Test
    fun `无能力时enable如实标记未生效`() {
        val fw = FirewallManager(tempHome().absolutePath)
        if (!fw.iptablesUsable()) {
            val s = fw.enable()
            assertTrue(s.enabled)          // 策略层标记为已启用
            assertFalse(s.kernelApplied)   // 内核层如实反映未生效
            assertTrue(s.note.contains("CAP_NET_ADMIN"))
        }
    }

    @Test
    fun `firewall工具status结构合法`() = runBlocking {
        val tool = ServerFirewallTool(FirewallManager(tempHome().absolutePath))
        val r = tool.execute(mapOf("action" to js("\"status\"")))
        assertTrue(r is ToolResult.Success)
        // 非法 list 返回 Error
        assertTrue(tool.execute(mapOf(
            "action" to js("\"add\""), "list" to js("\"bogus\"")
        )) is ToolResult.Error)
    }

    // ---------- 端口管理 ----------

    @Test
    fun `普通端口可加入白名单与黑名单`() {
        val fw = FirewallManager(tempHome().absolutePath)
        val allowed = fw.addRule("allow", FirewallManager.Rule(host = "api.example.com", port = 443))
        assertTrue(allowed.allow.any { it.host == "api.example.com" && it.port == 443 })
        val denied = fw.addRule("deny", FirewallManager.Rule(host = "ads.example.com", port = 9000))
        assertTrue(denied.deny.any { it.host == "ads.example.com" && it.port == 9000 })
        // 可移除
        assertTrue(fw.removeRule("allow", FirewallManager.Rule(host = "api.example.com", port = 443)).allow.isEmpty())
    }

    @Test
    fun `用户列举的高危端口在allow与deny都被拒绝且不写入`() {
        val home = tempHome()
        val fw = FirewallManager(home.absolutePath)
        listOf(80, 8888, 22, 3304).forEach { p ->
            listOf("allow", "deny").forEach { kind ->
                val reason = fw.validateRule(FirewallManager.Rule(host = "target.example.com", port = p))
                assertNotNull("端口 $p 在 $kind 操作应被拒绝", reason)
                assertTrue(reason!!.contains("高危端口"))
                val st = fw.addRule(kind, FirewallManager.Rule(host = "target.example.com", port = p))
                assertTrue("高危端口 $p 不应写入 $kind", st.allow.none { it.port == p } && st.deny.none { it.port == p })
            }
        }
        // 拒绝后规则未落盘污染，重启重读仍为空
        val reloaded = FirewallManager(home.absolutePath)
        assertTrue(reloaded.status().allow.isEmpty())
        assertTrue(reloaded.status().deny.isEmpty())
    }

    @Test
    fun `扩展高危端口同样被拒绝`() {
        val fw = FirewallManager(tempHome().absolutePath)
        listOf(20, 21, 23, 25, 110, 143, 445, 1433, 1521, 3306, 3389, 5432, 6379, 8080, 11211, 27017, 9200)
            .forEach { p ->
                assertNotNull("端口 $p 应被识别为高危", fw.validateRule(FirewallManager.Rule(port = p)))
            }
    }

    @Test
    fun `通用出网端口与通配端口不被误伤`() {
        val fw = FirewallManager(tempHome().absolutePath)
        // 443 是 HTTPS 主力端口、53 是 DNS，纳入黑名单会导致「收紧即断网」的反面效果，必须放行操作
        assertNull(fw.validateRule(FirewallManager.Rule(port = 443)))
        assertNull(fw.validateRule(FirewallManager.Rule(port = 53)))
        // port=0 通配（放行某域名全部端口）保持允许
        assertNull(fw.validateRule(FirewallManager.Rule(host = "example.com", port = 0)))
    }

    @Test
    fun `防火墙工具对高危端口返回PROTECTED_PORT错误`() = runBlocking {
        val tool = ServerFirewallTool(FirewallManager(tempHome().absolutePath))
        listOf(80, 8888, 22, 3304, 3306).forEach { p ->
            listOf("add", "remove").forEach { action ->
                val r = tool.execute(mapOf(
                    "action" to js("\"$action\""), "list" to js("\"allow\""), "port" to js("\"$p\"")
                ))
                assertTrue("$action port=$p 应返回 Error", r is ToolResult.Error)
                assertEquals("PROTECTED_PORT", (r as ToolResult.Error).code)
                assertTrue(r.message.contains("高危端口"))
            }
        }
    }

    @Test
    fun `status输出暴露受保护端口列表`() = runBlocking {
        val tool = ServerFirewallTool(FirewallManager(tempHome().absolutePath))
        val r = tool.execute(mapOf("action" to js("\"status\"")))
        assertTrue(r is ToolResult.Success)
        val transport = r.toTransportString()
        assertTrue("status 应包含 protectedPorts", transport.contains("protectedPorts"))
        // 直接校验集合内容，避免对传输字符串格式做脆弱断言
        assertTrue(FirewallManager.PROTECTED_PORTS.containsAll(listOf(22, 80, 3304, 8888)))
        assertFalse("443 不应被保护", FirewallManager.PROTECTED_PORTS.contains(443))
        assertFalse("53 不应被保护", FirewallManager.PROTECTED_PORTS.contains(53))
    }
}
