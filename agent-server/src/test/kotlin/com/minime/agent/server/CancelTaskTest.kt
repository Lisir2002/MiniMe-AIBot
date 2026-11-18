package com.minime.agent.server

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用户主动停止云端任务的持久化根基：
 * - 取消任务后状态必须是终态 CANCELLED（否则进程重启会被 resumeUnfinished 误续跑）；
 * - 取消是终态，需清理该任务残留的 pending 审批（幽灵卡片）；
 * - TASK_CANCELLED 事件能落库并被 App 补拉读到，作为「已停止」收尾依据；
 * - 可按 taskId 反查所属会话，落事件到正确会话。
 */
class CancelTaskTest {

    private lateinit var dbFile: File
    private lateinit var store: EventStore

    private val provider = ProviderConfigDto(
        type = "anthropic", apiKey = "k", baseUrl = "https://x", model = "m"
    )

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("agent-cancel-test", ".db").also { it.delete() }
        store = EventStore(dbFile.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        store.close()
        dbFile.delete()
    }

    @Test
    fun cancelTask_isTerminalAndCleansPendingDecision() {
        val taskId = store.createTask("s1", provider, "basic")
        store.setTaskStatus(taskId, TaskStatus.RUNNING)
        store.registerPending("d1", "s1", taskId, "Bash", "long running")
        assertEquals(1, store.pendingDecisionsFor("s1").size)

        // 模拟 cancelTask 的落库动作
        store.setTaskStatus(taskId, TaskStatus.CANCELLED)
        store.append(NewEvent("s1", EventKind.TASK_CANCELLED, taskId, text = "用户已停止"))

        assertEquals(TaskStatus.CANCELLED, store.getTaskStatus(taskId))
        assertTrue(store.pendingDecisionsFor("s1").isEmpty(), "取消应清理幽灵审批卡片")
    }

    @Test
    fun cancelledTask_isNotResumed() {
        val taskId = store.createTask("s1", provider, "basic")
        store.setTaskStatus(taskId, TaskStatus.CANCELLED)
        // unfinishedTasks 只含 QUEUED/RUNNING/WAITING_APPROVAL，cancelled 不应出现
        assertTrue(store.unfinishedTasks().none { it.taskId == taskId }, "已取消任务不得被重启续跑")
    }

    @Test
    fun taskCancelledEvent_isPersistedAndReadable() {
        val taskId = store.createTask("s1", provider, "basic")
        store.append(NewEvent("s1", EventKind.TASK_CANCELLED, taskId, text = "用户已停止"))

        val events = store.eventsAfter("s1", 0L)
        assertTrue(events.any { it.kind == EventKind.TASK_CANCELLED && it.taskId == taskId })
    }

    @Test
    fun sessionIdCanBeLookedUpByTaskId() {
        val taskId = store.createTask("session-xyz", provider, "strict")
        assertEquals("session-xyz", store.getSessionIdForTask(taskId))
        assertNull(store.getSessionIdForTask("not-exist"))
    }
}
