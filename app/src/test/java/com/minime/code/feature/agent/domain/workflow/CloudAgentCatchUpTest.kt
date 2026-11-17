package com.minime.code.feature.agent.domain.workflow

import android.content.Context
import com.minime.code.feature.agent.data.remote.cloudagent.CloudAgentApi
import com.minime.code.feature.agent.data.remote.cloudagent.EventsResp
import com.minime.code.feature.agent.data.remote.cloudagent.PendingDecisionDto
import com.minime.code.feature.agent.data.remote.cloudagent.StoredEventDto
import com.minime.code.feature.agent.data.local.dao.ChatSessionDao
import com.minime.code.feature.agent.domain.session.MessagePersistenceUseCase
import com.minime.code.feature.agent.presentation.MessageRole
import com.minime.code.feature.settings.data.repository.CloudAgentSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完全云端 Agent 断线补拉的核心不变量：
 *  1) 从持久化游标续接，不从 0 回放（进程被杀/熄屏不丢事件）；
 *  2) 落库使用确定性 messageId（与前台实时流一致），补拉不会重复插入；
 *  3) 尚未处理的待决策被恢复，供 App 重开后重建审批卡片。
 */
class CloudAgentCatchUpTest {

    private val api = mockk<CloudAgentApi>()
    private val settings = mockk<CloudAgentSettingsRepository>(relaxed = true)
    private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val chatSessionDao = mockk<ChatSessionDao>(relaxed = true)
    private val targetResolver = mockk<CloudTargetResolver>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private fun catchUp(): CloudAgentCatchUp {
        // 让目标解析统一指向测试服务器，保持原有"单服务器"用例语义不变。
        coEvery { targetResolver.resolve(any()) } returns CloudTargetResolver.CloudTarget(
            connectionId = null, baseUrl = "http://example.com:8080", token = "t"
        )
        return CloudAgentCatchUp(api, settings, persistence, chatSessionDao, targetResolver, context)
    }

    private fun assistantEvent(seq: Long, text: String) = StoredEventDto(
        seq = seq, sessionId = "cloud-1", kind = "assistant_text", text = text
    )

    @Test
    fun syncSession_usesPersistedCursor_notZero() = runTest {
        val appSid = "app-session-1"
        coEvery { settings.isEnabled() } returns true
        coEvery { settings.serverUrl() } returns "http://example.com:8080"
        coEvery { settings.token() } returns "t"
        coEvery { settings.getCloudSessionId(appSid, any()) } returns "cloud-1"
        coEvery { settings.getPollCursor(appSid, any()) } returns 41L // 上次已拉到 41
        coEvery { api.events(any(), any(), "cloud-1", 41L) } returns EventsResp(
            events = listOf(
                assistantEvent(42, "hello"), assistantEvent(43, "world"),
                StoredEventDto(44, "cloud-1", "task_completed")
            ),
            nextSeq = 44L,
            taskStatus = "completed"
        )
        every { context.getString(any(), any()) } returns "执行中"

        val result = catchUp().syncSession(appSid)!!

        assertEquals(3, result.applied)
        assertTrue(result.terminated)
        // 从 41 续接，绝不调用 after=0
        coVerify(exactly = 0) { api.events(any(), any(), any(), 0L) }
        coVerify { api.events(any(), any(), "cloud-1", 41L) }
        // 游标推进到 44
        coVerify { settings.setPollCursor(appSid, any(), 44L) }
    }

    @Test
    fun assistantMessageId_isDeterministicByIdSeq() {
        // 确定性 id 是「补拉不重复插入」的根基：同一 seq 无论正文/来源如何，id 恒定。
        assertEquals("cloud_a_7", assistantEvent(7, "答案").assistantMessageId)
        assertEquals("cloud_a_7", assistantEvent(7, "不同正文").assistantMessageId)
        assertEquals("cloud_a_8", assistantEvent(8, "答案").assistantMessageId)
        assertEquals("tool_call-9", StoredEventDto(1, "c", "tool_call_finished", toolCallId = "call-9").toolMessageId)
    }

    @Test
    fun syncSession_persistsEveryEvent() = runTest {
        val appSid = "app-session-1"
        val persistedIds = mutableListOf<String>()
        coEvery { settings.isEnabled() } returns true
        coEvery { settings.serverUrl() } returns "http://example.com:8080"
        coEvery { settings.getCloudSessionId(appSid, any()) } returns "cloud-1"
        coEvery { settings.getPollCursor(appSid, any()) } returns 0L
        coEvery { api.events(any(), any(), "cloud-1", 0L) } returns EventsResp(
            events = listOf(assistantEvent(7, "答案"), assistantEvent(8, "补充")),
            nextSeq = 8L, taskStatus = "completed"
        )
        // 记录每次落库的 id，验证用了服务端 seq 派生的确定性 id
        coEvery {
            persistence.persist(
                role = MessageRole.ASSISTANT,
                sessionId = any(), content = any(), id = capture(persistedIds),
                toolCalls = any(), toolCallId = any(), toolName = any(), toolArgs = any(),
                isError = any(), reasoning = any(), signature = any(), thinkingBlocksJson = any(),
                attachments = any(), inputTokens = any(), outputTokens = any(),
                cachedInputTokens = any(), isCompacted = any(), isContextExcluded = any()
            )
        } returns Unit

        val result = catchUp().syncSession(appSid)!!

        assertEquals(2, result.applied)
        assertEquals(listOf("cloud_a_7", "cloud_a_8"), persistedIds)
    }

    @Test
    fun syncSession_restoresPendingDecision() = runTest {
        val appSid = "app-session-1"
        coEvery { settings.isEnabled() } returns true
        coEvery { settings.serverUrl() } returns "http://example.com:8080"
        coEvery { settings.getCloudSessionId(appSid, any()) } returns "cloud-1"
        coEvery { settings.getPollCursor(appSid, any()) } returns 5L
        val pending = PendingDecisionDto(
            decisionId = "tool-call-9", taskId = "task-1",
            toolName = "Bash", argsPreview = "echo hi", sessionId = "cloud-1"
        )
        coEvery { api.events(any(), any(), "cloud-1", 5L) } returns EventsResp(
            events = emptyList(), nextSeq = 5L,
            taskStatus = "waiting_approval", pendingDecisions = listOf(pending)
        )

        val result = catchUp().syncSession(appSid)!!

        assertEquals(1, result.pendingDecisions.size)
        assertEquals("tool-call-9", result.pendingDecisions.first().decisionId)
        assertEquals("Bash", result.pendingDecisions.first().toolName)
    }

    // ---- 云端任务"仍在运行"判定：删后台重开后据此重建运行态并启动跟随轮询 ----

    private fun resultForStatus(status: String): CloudAgentCatchUp.Result {
        var result: CloudAgentCatchUp.Result? = null
        runTest {
            val appSid = "app-session-running"
            coEvery { settings.isEnabled() } returns true
            coEvery { settings.serverUrl() } returns "http://example.com:8080"
            coEvery { settings.getCloudSessionId(appSid, any()) } returns "cloud-1"
            coEvery { settings.getPollCursor(appSid, any()) } returns 0L
            val terminated = status == "completed" || status == "failed"
            coEvery { api.events(any(), any(), "cloud-1", 0L) } returns EventsResp(
                events = if (terminated) listOf(StoredEventDto(1, "cloud-1", "task_completed")) else emptyList(),
                nextSeq = 1L, taskStatus = status
            )
            result = catchUp().syncSession(appSid)!!
        }
        return result!!
    }

    @Test
    fun runningStatuses_areReportedRunning() {
        // 这些状态下任务未终态：App 必须重建"执行中"并继续跟随，否则删后台后看起来像断了。
        assertTrue(resultForStatus("running").running)
        assertTrue(resultForStatus("queued").running)
        assertTrue(resultForStatus("waiting_approval").running)
    }

    @Test
    fun terminalStatuses_areNotRunning() {
        org.junit.Assert.assertFalse(resultForStatus("completed").running)
        org.junit.Assert.assertFalse(resultForStatus("failed").running)
    }

    @Test
    fun idleStatus_isNotRunning() {
        // 服务端无任务时返回 idle：不应误判为运行中而挂一个永不停的跟随协程。
        org.junit.Assert.assertFalse(resultForStatus("idle").running)
    }
}
