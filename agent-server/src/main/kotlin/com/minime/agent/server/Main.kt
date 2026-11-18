package com.minime.agent.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import com.minime.agent.server.modules.LocalFileAccessProvider
import com.minime.agent.server.modules.ServerSkillRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main(args: Array<String>) {
    val dbPath = System.getenv("MINIME_DB") ?: "/data/agent-server/events.db"
    val workspaceRoot = System.getenv("MINIME_WORKSPACE") ?: "/workspace"
    val port = (System.getenv("MINIME_PORT") ?: "8080").toInt()
    val token = System.getenv("MINIME_TOKEN") ?: ""

    val store = EventStore(dbPath)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val runner = AgentRunner(store, workspaceRoot, scope)
    // 环境数据访问：与工具链共用同一套容器文件系统/skill 仓库，供 App 侧边栏按目标读取。
    val fileAccess = LocalFileAccessProvider(workspacePath = workspaceRoot)
    val skillRepository = ServerSkillRepository(fileAccess)

    // 崩溃续跑：服务重启后恢复未完成任务（App 删后台/进程被杀不影响云端执行）
    runner.resumeUnfinished()

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        configureServer(store, runner, token, fileAccess, skillRepository)
    }.start(wait = true)
}
