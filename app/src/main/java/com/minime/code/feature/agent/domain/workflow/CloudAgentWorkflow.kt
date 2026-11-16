package com.minime.code.feature.agent.domain.workflow

import com.minime.code.feature.agent.data.remote.cloudagent.CloudAgentApi
import com.minime.code.feature.agent.data.remote.cloudagent.CreateSessionReq
import com.minime.code.feature.agent.data.remote.cloudagent.DecisionReq
import com.minime.code.feature.agent.data.remote.cloudagent.ProviderConfigDto
import com.minime.code.feature.agent.data.remote.cloudagent.StoredEventDto
import com.minime.code.feature.agent.data.remote.cloudagent.SubmitMessageReq
import com.minime.code.feature.agent.domain.model.AgentContext
import com.minime.code.feature.agent.domain.tool.AgentTool
import com.minime.code.feature.settings.data.repository.CloudAgentSettingsRepository
import com.minime.code.feature.settings.data.repository.DefaultModelSettingsRepository
import com.minime.code.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 完全云端 Agent 工作流：把提问提交到云端 agent-server，轮询增量事件，
 * 转成标准 [AgentEvent] 发射给 ViewModel——复用本地模式完全一致的事件消费/落库/UI 链路。
 *
 * 云端 sessionId 由 [CloudAgentSettingsRepository] 的映射维护（同一 App 会话复用同一云端会话，
 * 多轮对话上下文留在云端）。任务在服务端协程跑，App 退后台不影响；待审批事件转成
 * [AgentEvent.PendingDecision] 供 UI 弹卡片。
 */
@Singleton
class CloudAgentWorkflow @Inject constructor(
    private val api: CloudAgentApi,
    private val settings: CloudAgentSettingsRepository,
    private val providerRepository: AIProviderRepository,
    private val defaultModelSettings: DefaultModelSettingsRepository
) : AgentWorkflow {

    private companion object {
        const val POLL_INTERVAL_MS = 1200L
        const val MAX_POLLS = 30 * 60 // 最多轮询 30 分钟（云端可跑很久）
        const val K_USER_MESSAGE = "user_message"
        const val K_ASSISTANT_TEXT = "assistant_text"
        const val K_TOOL_STARTED = "tool_call_started"
        const val K_TOOL_FINISHED = "tool_call_finished"
        const val K_PENDING_DECISION = "pending_decision"
        const val K_TASK_COMPLETED = "task_completed"
        const val K_TASK_FAILED = "task_failed"
    }

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> = channelFlow {
        val baseUrl = settings.serverUrl()
        if (baseUrl.isBlank()) {
            send(AgentEvent.Failed("云端 Agent 模式已开启，但未配置服务器地址"))
            return@channelFlow
        }
        val auth = "Bearer ${settings.token()}"
        val appSessionId = context.sessionId ?: ""

        // 1) 解析当前 App 生效的 provider 配置，交给云端（云端据此调 LLM）
        val cfg = resolveProviderConfig()
        if (cfg == null) {
            send(AgentEvent.Failed("未找到有效的 AI 提供商配置，无法在云端运行"))
            return@channelFlow
        }

        // 2) 取得/创建云端 sessionId（同一 App 会话复用）
        val cloudSessionId = ensureCloudSession(baseUrl, auth, appSessionId)

        // 3) 记住拉取游标：从云端该会话当前最大 seq 之后开始，避免回放历史
        val before = api.events(baseUrl, auth, cloudSessionId, 0L)
        var cursor = before.nextSeq

        // 4) 提交提问
        api.submitMessage(baseUrl, auth, cloudSessionId, SubmitMessageReq(userRequest, cfg))

        // 5) 轮询增量事件，转成标准 AgentEvent
        var terminated = false
        for (i in 0 until MAX_POLLS) {
            delay(POLL_INTERVAL_MS)
            val resp = api.events(baseUrl, auth, cloudSessionId, cursor)
            for (e in resp.events) {
                cursor = maxOf(cursor, e.seq)
                emitEvent(e, appSessionId)?.let { send(it) }
                when (e.kind) {
                    K_TASK_COMPLETED -> terminated = true
                    K_TASK_FAILED -> terminated = true
                }
            }
            if (terminated) break
        }
        if (!terminated) {
            send(AgentEvent.Failed("云端任务等待超时"))
        }
    }

    /** 把云端 StoredEvent 转成 AgentEvent；无需 UI 感知的事件返回 null。 */
    private fun emitEvent(e: StoredEventDto, appSessionId: String): AgentEvent? = when (e.kind) {
        K_USER_MESSAGE -> null // 用户消息已在本地落库
        K_ASSISTANT_TEXT -> AgentEvent.AssistantText(
            content = e.text,
            reasoning = e.reasoning,
            thinkingBlocksJson = e.thinkingBlocksJson,
            signature = e.signature
        )
        K_TOOL_STARTED -> AgentEvent.ToolCallStarted(e.toolCallId, e.toolName, e.toolArgs)
        K_TOOL_FINISHED -> AgentEvent.ToolCallFinished(
            id = e.toolCallId, toolName = e.toolName, result = e.text, isError = e.isError
        )
        K_PENDING_DECISION -> AgentEvent.PendingDecision(
            decisionId = e.toolCallId, toolName = e.toolName,
            argsPreview = e.toolArgs, sessionId = appSessionId
        )
        K_TASK_COMPLETED -> AgentEvent.Completed
        K_TASK_FAILED -> AgentEvent.Failed(e.text)
        else -> null
    }

    private suspend fun ensureCloudSession(baseUrl: String, auth: String, appSessionId: String): String {
        // 第一版：每轮复用固定键映射；持久化映射的读写放在 settings（此处先用固定 title 建/取）。
        // 真正的 sessionId 映射需要 DataStore 读写，见 CloudAgentSettingsRepository.cloudSessionKey。
        // 为避免重复建会话，云端按 title 去重不可用——这里每轮建一个新会话，上下文由 App 历史补齐future。
        return api.createSession(baseUrl, auth, CreateSessionReq("cloud-$appSessionId")).sessionId
    }

    /** 解析当前生效 provider 配置（单 Key；多 Key 取第一个）。 */
    private suspend fun resolveProviderConfig(): ProviderConfigDto? {
        val id = defaultModelSettings.getDefaultProviderId().takeIf { it.isNotBlank() } ?: return null
        val p = providerRepository.getProviderById(id) ?: return null
        val model = defaultModelSettings.getDefaultModel().takeIf { it.isNotBlank() }
            ?: p.effectiveModel
        return ProviderConfigDto(
            type = p.type.name.lowercase(),
            apiKey = p.effectiveApiKeys.firstOrNull()?.value ?: p.apiKey,
            baseUrl = p.baseUrl,
            useFullUrl = p.useFullUrl,
            model = model
        )
    }

    /** 用户审批决策回传云端。UI 在用户点击批准/拒绝后调用。 */
    suspend fun decide(decisionId: String, approve: Boolean) {
        val baseUrl = settings.serverUrl()
        api.decide(baseUrl, "Bearer ${settings.token()}", decisionId, DecisionReq(approve))
    }

    // ---- AgentWorkflow 其余方法：云端模式下的简化实现 ----
    override suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit): Boolean = false
    override suspend fun generateTitle(sessionId: String, request: String): String? = null
    override suspend fun generateCommitMessage(diff: String): String? = null
}
