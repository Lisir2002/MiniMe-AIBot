package com.minime.code.feature.agent.domain.subagent

import com.minime.code.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局子代理定义来源：`~/.minimecode/agents`，跨项目共享。以容器路径经 [FileAccessProvider] 访问，
 * 跟随当前执行环境——本地模式落 App 私有 `filesDir/minimecode/agents`（容器内 `/root/.minimecode/agents`），
 * 远程模式经 SSH 落服务器用户 home 下的 `.minimecode/agents`。远程是独立执行环境，读写均以远端为准。
 */
@Singleton
class GlobalDirectoryAgentSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentDefinitionSource {

    val agentsRoot: String = "~/.minimecode/agents"

    override fun listDefinitions(): List<AgentDefinition> =
        AgentDefinitionDirectoryScanner.scan(fileAccess, agentsRoot)
}
