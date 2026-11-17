package com.minime.code.feature.agent.domain.workflow

import com.minime.code.feature.agent.domain.model.AgentContext
import com.minime.code.feature.agent.domain.tool.AgentTool
import com.minime.code.feature.settings.data.repository.CloudAgentSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Agent 工作流的路由层：根据「完全云端 Agent 模式」开关，把调用转发到本地状态机或云端服务。
 *
 * 对 ViewModel 完全透明——它始终只依赖 [AgentWorkflow] 接口，无需感知执行位置。
 * 开关在每次 executeEvents 收集时读取，用户切换模式后下一轮提问即时生效。
 */
@Singleton
class DelegatingAgentWorkflow @Inject constructor(
    @param:Named("local") private val local: AgentWorkflow,
    private val cloud: CloudAgentWorkflow,
    private val settings: CloudAgentSettingsRepository,
    private val targetResolver: CloudTargetResolver
) : AgentWorkflow {

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> = channelFlow {
        // 路由按会话的执行目标决定：绑定了云服务器→云端，否则本机。
        // 同一 App 的不同会话可同时处在不同后端，互不干扰。
        val useCloud = targetResolver.isCloudSession(context.sessionId)
        val source = if (useCloud) {
            cloud.executeEvents(userRequest, context, tools)
        } else {
            local.executeEvents(userRequest, context, tools)
        }
        source.collect { send(it) }
    }

    override suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit): Boolean =
        if (targetResolver.isCloudSession(sessionId)) cloud.compactSession(sessionId, onEvent)
        else local.compactSession(sessionId, onEvent)

    override suspend fun generateTitle(sessionId: String, request: String): String? =
        if (targetResolver.isCloudSession(sessionId)) cloud.generateTitle(sessionId, request)
        else local.generateTitle(sessionId, request)

    override suspend fun generateCommitMessage(diff: String): String? =
        if (settings.isEnabled()) cloud.generateCommitMessage(diff) else local.generateCommitMessage(diff)
}
