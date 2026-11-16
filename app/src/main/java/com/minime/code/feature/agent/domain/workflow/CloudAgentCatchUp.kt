package com.minime.code.feature.agent.domain.workflow

import android.content.Context
import com.minime.code.R
import com.minime.code.feature.agent.data.remote.cloudagent.CloudAgentApi
import com.minime.code.feature.agent.data.remote.cloudagent.PendingDecisionDto
import com.minime.code.feature.agent.data.remote.cloudagent.StoredEventDto
import com.minime.code.feature.agent.domain.session.MessagePersistenceUseCase
import com.minime.code.feature.agent.domain.session.SessionUseCase
import com.minime.code.feature.agent.presentation.MessageRole
import com.minime.code.feature.settings.data.repository.CloudAgentSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 完全云端 Agent 的「断线补拉 / 增量同步」器。
 *
 * 背景：云端 agent-server 在独立于 App 的服务端协程里执行任务，App 熄屏/删后台期间照常推进。
 * 但事件原本只由 [CloudAgentWorkflow] 的前台轮询消费——进程被杀后轮询协程随之销毁，重开 App
 * 没有任何入口把积压事件拉回来，表现为「对话中断」。
 *
 * 本类把所有「拉取 + 落库 + 推进游标」收敛到与 App 生命周期无关的纯数据层：
 *   - 游标持久化在 [CloudAgentSettingsRepository]，进程重启不丢；
 *   - 落库使用确定性 messageId（[StoredEventDto.assistantMessageId] / [toolMessageId]），
 *     与前台实时流共用，Room 的 REPLACE 保证幂等，补拉不会重复插入气泡；
 *   - [syncSession] 可在 App 启动、回前台、切换到某会话时安全重复调用（幂等）。
 *
 * 不涉及任何 UI 副作用（状态机/通知/标题），那些仍由前台实时流的 ViewModel collect 负责。
 */
@Singleton
class CloudAgentCatchUp @Inject constructor(
    private val api: CloudAgentApi,
    private val settings: CloudAgentSettingsRepository,
    private val messagePersistence: MessagePersistenceUseCase,
    @param:ApplicationContext private val context: Context
) {
    /** 同一会话的补拉串行化，避免「回前台」与「切会话」并发触发导致游标竞态。 */
    private val locks = mutableMapOf<String, Mutex>()

    data class Result(
        val applied: Int,
        val pendingDecisions: List<PendingDecisionDto>,
        val taskStatus: String,
        val terminated: Boolean
    )

    /** 若当前未启用云端模式或会话未建立云端映射，返回 null（调用方直接跳过）。 */
    suspend fun syncSession(appSessionId: String): Result? {
        if (!settings.isEnabled()) return null
        val baseUrl = settings.serverUrl()
        if (baseUrl.isBlank() || appSessionId.isBlank()) return null
        val cloudSessionId = settings.getCloudSessionId(appSessionId, baseUrl) ?: return null
        val auth = "Bearer ${settings.token()}"

        val mutex = synchronized(locks) { locks.getOrPut(appSessionId) { Mutex() } }
        return mutex.withLock {
            var cursor = settings.getPollCursor(appSessionId, baseUrl)
            val resp = api.events(baseUrl, auth, cloudSessionId, cursor)
            var applied = 0
            var terminated = false
            for (e in resp.events) {
                cursor = maxOf(cursor, e.seq)
                applyToLocal(appSessionId, e)
                applied++
                if (e.kind == K_TASK_COMPLETED || e.kind == K_TASK_FAILED) terminated = true
            }
            // 游标推进到本次拉到的最新位置；即使随后进程被杀，重开也从这里续接
            settings.setPollCursor(appSessionId, baseUrl, maxOf(cursor, resp.nextSeq))
            Result(applied, resp.pendingDecisions, resp.taskStatus, terminated)
        }
    }

    /** 批量同步所有「本服务器下建立过云端映射」的会话（App 冷启动/回前台调用）。 */
    suspend fun syncAll(): Map<String, Result> {
        if (!settings.isEnabled()) return emptyMap()
        val baseUrl = settings.serverUrl()
        if (baseUrl.isBlank()) return emptyMap()
        val out = linkedMapOf<String, Result>()
        for (appSessionId in settings.allCloudAppSessionIds(baseUrl)) {
            syncSession(appSessionId)?.let { out[appSessionId] = it }
        }
        return out
    }

    /** 把一条云端事件幂等落库为本地消息。字段与前台实时流（ViewModel collect）保持一致。 */
    private suspend fun applyToLocal(appSessionId: String, e: StoredEventDto) {
        when (e.kind) {
            K_ASSISTANT_TEXT -> {
                // content 为空但带 thinking 时也要落库（思考过程展示）；完全空则跳过
                if (e.text.isEmpty() && e.reasoning.isEmpty() && e.thinkingBlocksJson.isEmpty()) return
                messagePersistence.persist(
                    sessionId = appSessionId,
                    role = MessageRole.ASSISTANT,
                    content = e.text,
                    id = e.assistantMessageId,
                    reasoning = e.reasoning.ifEmpty { null },
                    signature = e.signature.ifEmpty { null },
                    thinkingBlocksJson = e.thinkingBlocksJson.ifEmpty { null }
                )
            }
            K_TOOL_STARTED -> {
                messagePersistence.persist(
                    sessionId = appSessionId,
                    role = MessageRole.TOOL,
                    content = "${SessionUseCase.PENDING_TOOL_MARKER} ${
                        context.getString(R.string.agent_tool_executing, e.toolName)
                    }",
                    id = e.toolMessageId,
                    toolCallId = e.toolCallId,
                    toolName = e.toolName,
                    toolArgs = e.toolArgs.ifEmpty { null },
                    isError = false
                )
            }
            K_TOOL_FINISHED -> {
                messagePersistence.persist(
                    sessionId = appSessionId,
                    role = MessageRole.TOOL,
                    content = e.text,
                    id = e.toolMessageId,
                    toolCallId = e.toolCallId,
                    toolName = e.toolName,
                    toolArgs = e.toolArgs.ifEmpty { null },
                    isError = e.isError
                )
            }
            // user_message 本地发送时已落库；pending_decision/task_* 仅影响状态，不落库
        }
        // 落库后让历史缓存失效，确保 messagesState 从 Room 重新读到补拉的消息
        messagePersistence.invalidateHistory(appSessionId)
    }

    private companion object {
        const val K_ASSISTANT_TEXT = "assistant_text"
        const val K_TOOL_STARTED = "tool_call_started"
        const val K_TOOL_FINISHED = "tool_call_finished"
        const val K_TASK_COMPLETED = "task_completed"
        const val K_TASK_FAILED = "task_failed"
    }
}
