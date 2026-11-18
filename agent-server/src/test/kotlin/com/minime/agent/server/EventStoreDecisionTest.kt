package com.minime.agent.server

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 待审批决策持久化：这是「App 删后台/熄屏后重开能恢复审批卡片」的服务端根基。
 * 决策状态必须落 SQLite，而非只放内存 map（进程重启会丢）。
 */
class EventStoreDecisionTest {

    private lateinit var dbFile: File
    private lateinit var store: EventStore

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("agent-store-test", ".db").also { it.delete() }
        store = EventStore(dbFile.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        store.close()
        dbFile.delete()
    }

    @Test
    fun registeredDecision_isReturnedAsPending() {
        store.registerPending("d1", "s1", "t1", "Bash", "echo hi")

        val pending = store.pendingDecisionsFor("s1")
        assertEquals(1, pending.size)
        assertEquals("d1", pending.first().decisionId)
        assertEquals("Bash", pending.first().toolName)
        assertEquals("echo hi", pending.first().argsPreview)
    }

    @Test
    fun resolveDecision_removesFromPending_andIsIdempotent() {
        store.registerPending("d2", "s1", "t1", "Bash", "rm -rf x")

        assertTrue(store.resolvePending("d2", approve = true))
        assertTrue(store.pendingDecisionsFor("s1").isEmpty())
        // 重复决策返回 false（幂等，防重复审批）
        assertFalse(store.resolvePending("d2", approve = false))
    }

    @Test
    fun terminalTask_clearsResidualPendingDecision() {
        store.registerPending("d3", "s1", "t1", "Bash", "echo bye")
        assertEquals(1, store.pendingDecisionsFor("s1").size)

        // 任务因故直接进终态（未走审批）：不应留下幽灵待决策卡片
        store.setTaskStatus("t1", TaskStatus.COMPLETED)

        assertTrue(store.pendingDecisionsFor("s1").isEmpty())
    }

    @Test
    fun loginPending_isSeparatedFromDecisions_andRestored() {
        // 登录项与审批项共存于同一会话：必须分开返回，否则 App 会把登录误当二元审批卡
        store.registerPending("d5", "s1", "t1", "Bash", "echo hi")
        store.registerPending("login:login-42", "s1", "t1", "login", """{"url":"https://example.com/login","reason":"需要登录"}""")

        val decisions = store.pendingDecisionsFor("s1")
        assertEquals(1, decisions.size)
        assertEquals("d5", decisions.first().decisionId)

        val logins = store.pendingLoginsFor("s1")
        assertEquals(1, logins.size)
        assertEquals("login-42", logins.first().loginId)
        assertEquals("https://example.com/login", logins.first().url)
        assertEquals("需要登录", logins.first().reason)
    }

    @Test
    fun loginPending_resolvedIsRemoved() {
        store.registerPending("login:login-99", "s1", "t1", "login", """{"url":"https://x","reason":""}""")
        assertEquals(1, store.pendingLoginsFor("s1").size)

        // 走与审批同一套 resolvePending（/login/{id}/complete 调用），登录后应从待恢复列表消失
        assertTrue(store.resolvePending("login:login-99", approve = true))
        assertTrue(store.pendingLoginsFor("s1").isEmpty())
    }

    @Test
    fun decisionPersistsAcrossStoreReopen() {
        store.registerPending("d4", "s1", "t1", "Bash", "echo persist")
        store.close()

        // 模拟服务进程重启：新 EventStore 指向同一数据库文件
        store = EventStore(dbFile.absolutePath)
        val pending = store.pendingDecisionsFor("s1")
        assertEquals(1, pending.size)
        assertEquals("d4", pending.first().decisionId)

        // 进程重启后内存 deferred 丢失，但用户仍须能对恢复出来的卡片做决策
        assertTrue(store.resolvePending("d4", approve = false))
        assertTrue(store.pendingDecisionsFor("s1").isEmpty())
    }
}
