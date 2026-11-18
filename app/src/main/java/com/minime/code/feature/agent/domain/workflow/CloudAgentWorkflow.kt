package com.minime.code.feature.agent.domain.workflow

import com.google.gson.JsonParser
import com.minime.code.core.util.FileLogger
import com.minime.code.feature.agent.data.remote.cloudagent.CloudAgentApi
import com.minime.code.feature.agent.data.remote.cloudagent.CreateSessionReq
import com.minime.code.feature.agent.data.remote.cloudagent.DecisionReq
import com.minime.code.feature.agent.data.remote.cloudagent.LoginInputReq
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
    private val defaultModelSettings: DefaultModelSettingsRepository,
    private val trustLevelRepository: com.minime.code.feature.settings.data.repository.TrustLevelRepository,
    private val targetResolver: CloudTargetResolver
) : AgentWorkflow {

    private companion object {
        const val POLL_INTERVAL_MS = 1200L
        const val MAX_POLLS = 30 * 60 // 最多轮询 30 分钟（云端可跑很久）
        const val K_USER_MESSAGE = "user_message"
        const val K_ASSISTANT_TEXT = "assistant_text"
        const val K_TOOL_STARTED = "tool_call_started"
        const val K_TOOL_FINISHED = "tool_call_finished"
        const val K_PENDING_DECISION = "pending_decision"
        const val K_LOGIN_REQUIRED = "login_required"
        const val K_LOGIN_SUCCEEDED = "login_succeeded"
        const val K_LOGIN_CANCELLED = "login_cancelled"
        const val K_TASK_COMPLETED = "task_completed"
        const val K_TASK_FAILED = "task_failed"
        const val K_TASK_CANCELLED = "task_cancelled"
    }

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> = channelFlow {
        val appSessionId = context.sessionId ?: ""
        // 从会话绑定的执行目标解析出服务器地址/令牌：多会话可各跑各的服务器。
        val target = targetResolver.resolve(appSessionId)
        if (target == null || target.baseUrl.isBlank()) {
            send(AgentEvent.Failed("云端 Agent 目标不可用，请检查服务器的执行目标配置"))
            return@channelFlow
        }
        val baseUrl = target.baseUrl
        val auth = "Bearer ${target.token}"

        // 1) 解析当前 App 生效的 provider 配置，交给云端（云端据此调 LLM）
        val cfg = resolveProviderConfig()
        if (cfg == null) {
            send(AgentEvent.Failed("未找到有效的 AI 提供商配置，无法在云端运行"))
            return@channelFlow
        }

        // 2) 取得/创建云端 sessionId（同一 App 会话复用）
        val cloudSessionId = ensureCloudSession(baseUrl, auth, appSessionId)

        // 3) 从持久化游标续接：进程被杀/熄屏期间云端照常产生的事件，重开后不会丢。
        //    前台实时流与「启动/回前台补拉」共用同一游标，避免重复回放或遗漏。
        var cursor = settings.getPollCursor(appSessionId, baseUrl)
        // 首次拉时 cursor=0，服务端会把历史事件全部返回；但 assistant/tool 用确定性 id 落库幂等，
        // 且 task_completed/failed 只改状态，故重放安全。为避免首次把旧事件当成本轮实时流 emit，
        // 先进一次「静默补拉」把游标推进到当前最新，再从这里等本轮新事件。
        if (cursor == 0L) {
            val warmup = api.events(baseUrl, auth, cloudSessionId, 0L)
            cursor = warmup.nextSeq
            settings.setPollCursor(appSessionId, baseUrl, cursor)
        }

        // 4) 提交提问（携带当前信任档位，云端据此裁决工具审批强度）
        val trustLevel = trustLevelRepository.current().wireName
        val submitResp = api.submitMessage(baseUrl, auth, cloudSessionId, SubmitMessageReq(userRequest, cfg, trustLevel))
        // 记住 taskId：用户点「停止」时据此精确取消云端任务（含正在进行的 LLM 调用）。
        settings.setRunningTaskId(appSessionId, baseUrl, submitResp.taskId)

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
                    K_TASK_CANCELLED -> terminated = true
                }
            }
            // 每轮推进持久化游标：即使此刻进程被杀，重开后也从这里续接，不丢事件
            settings.setPollCursor(appSessionId, baseUrl, cursor)
            if (terminated) break
        }
        // 任务到达终态（完成/失败/被取消）：清掉 taskId 映射，避免下一次停止误命中已结束任务。
        settings.clearRunningTaskId(appSessionId, baseUrl)
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
            signature = e.signature,
            messageId = e.assistantMessageId // 确定性 id：与补拉落库幂等
        )
        K_TOOL_STARTED -> AgentEvent.ToolCallStarted(e.toolCallId, e.toolName, e.toolArgs)
        K_TOOL_FINISHED -> AgentEvent.ToolCallFinished(
            id = e.toolCallId, toolName = e.toolName, result = e.text, isError = e.isError,
            argsPreview = e.toolArgs
        )
        K_PENDING_DECISION -> AgentEvent.PendingDecision(
            decisionId = e.toolCallId, toolName = e.toolName,
            argsPreview = e.toolArgs, sessionId = appSessionId
        )
        K_LOGIN_REQUIRED -> parseLoginRequired(e.text, appSessionId)
        // 登录成功/取消仅用于清除登录面板；任务会自动恢复（服务端重跑工具，随后照常推事件）
        K_LOGIN_SUCCEEDED, K_LOGIN_CANCELLED -> null
        K_TASK_COMPLETED -> AgentEvent.Completed
        K_TASK_FAILED -> AgentEvent.Failed(e.text)
        else -> null
    }

    /** 解析 LOGIN_REQUIRED 事件的 text（服务端为含 loginId/url/reason/taskId 的 JSON）。 */
    private fun parseLoginRequired(text: String, appSessionId: String): AgentEvent? {
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val loginId = obj.get("loginId")?.asString?.takeIf { it.isNotBlank() } ?: return null
        return AgentEvent.LoginRequired(
            loginId = loginId,
            url = obj.get("url")?.asString ?: "",
            reason = obj.get("reason")?.asString ?: "",
            taskId = obj.get("taskId")?.asString ?: "",
            sessionId = appSessionId
        )
    }

    private suspend fun ensureCloudSession(baseUrl: String, auth: String, appSessionId: String): String {
        // 同一 App 会话 + 同一服务器地址 → 复用同一云端 session，多轮上下文完整留在云端
        val cached = settings.getCloudSessionId(appSessionId, baseUrl)
        if (!cached.isNullOrBlank()) return cached
        val created = api.createSession(baseUrl, auth, CreateSessionReq("cloud-$appSessionId")).sessionId
        settings.setCloudSessionId(appSessionId, baseUrl, created)
        return created
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

    /** 用户审批决策回传云端。UI 在用户点击批准/拒绝后调用；[sessionId] 用于定位对应的云服务器。 */
    suspend fun decide(sessionId: String?, decisionId: String, approve: Boolean) {
        val target = targetResolver.resolve(sessionId)
            ?: throw IllegalStateException("审批回传失败：会话没有可用的云端目标")
        api.decide(target.baseUrl, "Bearer ${target.token}", decisionId, DecisionReq(approve))
    }

    // ---- 云端登录会话回传：登录面板把画面/输入经这里转发给 agent-server ------------------

    private suspend fun cloudTarget(sessionId: String?) = targetResolver.resolve(sessionId)
        ?: throw IllegalStateException("云端登录失败：会话没有可用的云端目标")

    /** 拉取登录会话的增量帧（画面在服务端渲染，App 只拿到 JPEG 像素）。 */
    suspend fun loginFrame(sessionId: String?, loginId: String, after: Long) =
        cloudTarget(sessionId).let { api.loginFrame(it.baseUrl, "Bearer ${it.token}", loginId, after) }

    /** 把手机的触摸/键盘事件注入云端浏览器。 */
    suspend fun loginInput(sessionId: String?, loginId: String, req: LoginInputReq) =
        cloudTarget(sessionId).let { api.loginInput(it.baseUrl, "Bearer ${it.token}", loginId, req) }

    /** 查询登录会话状态。 */
    suspend fun loginStatus(sessionId: String?, loginId: String) =
        cloudTarget(sessionId).let { api.loginStatus(it.baseUrl, "Bearer ${it.token}", loginId) }

    /** 订阅登录帧 WebSocket 推流（变化帧 + 状态合并）；Flow 结束即需降级到 HTTP 轮询。 */
    suspend fun loginStream(sessionId: String?, loginId: String) =
        cloudTarget(sessionId).let { api.loginStream(it.baseUrl, "Bearer ${it.token}", loginId) }

    /** 登录成功/取消，唤醒云端挂起的任务。 */
    suspend fun loginComplete(sessionId: String?, loginId: String, success: Boolean) =
        cloudTarget(sessionId).let { api.loginComplete(it.baseUrl, "Bearer ${it.token}", loginId, success) }

    /**
     * 主动停止会话在云端运行中的任务：从持久化映射取 taskId，发取消请求。
     * [sessionId] 为 App 会话 id（非云端 sessionId），用于定位服务器与 taskId。
     * @return true 表示成功发出取消（服务端确认取消了运行中的任务）；false 表示无任务可停或已结束。
     */
    suspend fun cancelBySession(sessionId: String?): Boolean {
        if (sessionId.isNullOrBlank()) return false
        val target = targetResolver.resolve(sessionId) ?: return false
        if (target.baseUrl.isBlank()) return false
        val taskId = settings.getRunningTaskId(sessionId, target.baseUrl) ?: return false
        return try {
            val resp = api.cancelTask(target.baseUrl, "Bearer ${target.token}", taskId)
            settings.clearRunningTaskId(sessionId, target.baseUrl)
            resp.ok
        } catch (e: Exception) {
            // 停止是用户主动操作：网络失败也要清本地映射，避免下次停止继续命中失效 taskId；
            // 云端任务会自然跑完/超时，本地 job 的取消已让 UI 立即停转。
            settings.clearRunningTaskId(sessionId, target.baseUrl)
            FileLogger.e("CloudAgentWorkflow", "取消云端任务失败: taskId=$taskId", e)
            false
        }
    }

    // ---- AgentWorkflow 其余方法：云端模式下的简化实现 ----
    override suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit): Boolean = false
    override suspend fun generateTitle(sessionId: String, request: String): String? = null
    override suspend fun generateCommitMessage(diff: String): String? = null
}
