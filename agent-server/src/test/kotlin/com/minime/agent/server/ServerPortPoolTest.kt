package com.minime.agent.server

import com.minime.agent.server.modules.FirewallManager
import com.minime.agent.server.modules.PortPoolManager
import com.minime.agent.server.modules.ProcessLifecycleManager
import com.minime.agent.server.modules.ServerPortTool
import com.minime.code.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 冷门端口池测试：空闲优先复用（LRU）、高危/知名端口排除、真实占用探测、
 * 进程回收自动归还、命令端口绑定、防火墙同步、工具输出结构。
 * 使用临时 HOME 与自定义端口段隔离，不依赖真实服务。
 */
class ServerPortPoolTest {
    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "minime-port-test-${System.nanoTime()}").also { it.mkdirs() }

    private fun js(json: String) = Json.parseToJsonElement(json)

    private fun pool(home: File, start: Int = 30000, end: Int = 30100, fw: FirewallManager? = null) =
        PortPoolManager(home.absolutePath, fw, start, end)

    @Test
    fun `分配端口落在冷门段且排除高危知名端口`() {
        val p = pool(tempHome())
        val r = p.acquire(owner = "svc")
        assertNotNull(r)
        assertTrue("应落在端口段", r!!.port in 30000..30100)
        assertFalse("不应是高危端口", r.port in FirewallManager.PROTECTED_PORTS)
        // 排除集合含高危 + 常见服务端口
        assertTrue(p.isExcluded(22))
        assertTrue(p.isExcluded(80))
        assertTrue(p.isExcluded(3304))
        assertTrue(p.isExcluded(3306))
        assertTrue(p.isExcluded(8080))
        assertTrue(p.isExcluded(80))
        assertFalse(p.isExcluded(30050))
        // 指定高危端口被拒
        assertNull("高危端口不可申请", p.acquire(owner = "x", preferredPort = 22))
        assertNull("知名端口不可申请", p.acquire(owner = "x", preferredPort = 80))
        assertNull("端口段外不可申请", p.acquire(owner = "x", preferredPort = 9999))
    }

    @Test
    fun `空闲优先复用最久未用的端口`() {
        val home = tempHome()
        val p = pool(home)
        // 分配两个不同端口并归还
        val a = p.acquire(owner = "a")!!.port
        val b = p.acquire(owner = "b")!!.port
        assertTrue(a != b)
        p.release(a)   // a 先归还
        Thread.sleep(5)
        p.release(b)   // b 后归还
        // 再分配：应优先复用最久未归还的 a（LRU），且 useCount 递增
        val reused = p.acquire(owner = "c")!!
        assertEquals(a, reused.port)
        assertEquals(2, reused.useCount)
        // 状态持久化：新实例能读回使用记录
        val reloaded = pool(home)
        assertEquals(2, reloaded.get(a)!!.useCount)
        assertTrue("复用端口仍标记占用", reloaded.get(a)!!.inUse)
        assertTrue("b 仍为空闲可复用", !reloaded.get(b)!!.inUse)
    }

    @Test
    fun `按owner批量归还与_stats统计`() {
        val p = pool(tempHome())
        val r1 = p.acquire(owner = "task-1")!!
        val r2 = p.acquire(owner = "task-1")!!
        p.acquire(owner = "task-2")  // 另一个 owner 不归还
        val freed = p.releaseByOwner("task-1")
        assertTrue(freed.containsAll(listOf(r1.port, r2.port)))
        assertEquals(2, freed.size)
        assertFalse(p.get(r1.port)!!.inUse)
        assertFalse(p.get(r2.port)!!.inUse)
        val s = p.stats()
        assertEquals(3, s["total"])
        assertEquals(1, s["inUse"])
        assertEquals(2, s["free"])
        assertEquals(2, s["reusable"])
        assertTrue(p.releaseByOwner("").isEmpty())
    }

    @Test
    fun `进程退出回调自动归还端口`() {
        val home = tempHome()
        val p = pool(home)
        val mgr = ProcessLifecycleManager()
        // 模拟端口绑定进程 tab，并登记进程
        val r = p.acquire(owner = "term-1")!!
        mgr.register("term-1", 999999L, "python3 -m http.server ${r.port}", kind = ProcessLifecycleManager.KIND_TERMINAL)
        // 绑定退出回调（与生产接线一致）
        mgr.onExit { id -> p.releaseByOwner(id) }
        // 手动 kill（pid 不存在 → AlreadyExited 也会触发回调）
        mgr.kill("term-1")
        assertFalse("进程退出后端口应自动归还", p.get(r.port)!!.inUse)
    }

    @Test
    fun `命令中的已分配端口自动绑定到owner`() {
        val p = pool(tempHome())
        val r = p.acquire(owner = "")!!  // 申请时无 owner
        val cmd = "python3 -m http.server ${r.port} --bind 0.0.0.0"
        val bound = p.bindPortsInCommand(cmd, "term-7")
        assertTrue(bound.contains(r.port))
        assertEquals("term-7", p.get(r.port)!!.owner)
        // 归还 owner 后端口释放
        assertTrue(p.releaseByOwner("term-7").contains(r.port))
    }

    @Test
    fun `防火墙同步_分配加入白名单归还移除`() {
        val home = tempHome()
        val fw = FirewallManager(home.absolutePath)
        val p = pool(home, fw = fw)
        val r = p.acquire(owner = "svc")!!
        assertTrue("分配后应加入白名单", fw.status().allow.any { it.port == r.port })
        p.release(r.port)
        assertFalse("归还后应移除白名单", fw.status().allow.any { it.port == r.port })
    }

    @Test
    fun `port工具acquire与高危端口拒绝`() = runBlocking {
        val tool = ServerPortTool(pool(tempHome()))
        val ok = tool.execute(mapOf("action" to js("\"acquire\""), "owner" to js("\"svc\"")))
        assertTrue(ok is ToolResult.Success)
        val port = (ok as ToolResult.Success).data.jsonObject["port"]!!.jsonPrimitive.content.toInt()
        assertTrue(port in 30000..30100)
        // 指定高危端口 → 无可用错误
        val bad = tool.execute(mapOf("action" to js("\"acquire\""), "port" to js("22")))
        assertTrue(bad is ToolResult.Error)
        assertEquals("NO_PORT_AVAILABLE", (bad as ToolResult.Error).code)
        // list / stats 结构
        assertTrue(tool.execute(mapOf("action" to js("\"list\""))) is ToolResult.Success)
        assertTrue(tool.execute(mapOf("action" to js("\"stats\""))) is ToolResult.Success)
    }
}
