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
    // 云端设置：单一实例共享给 agent loop 与 HTTP 端点，POST /settings 修改后下一轮立即生效。
    val settingsRepository = com.minime.agent.server.modules.ServerSettingsRepository()
    // 系统级能力：防火墙、进程生命周期、冷门端口池，单例共享给 agent 工具与 HTTP 端点。
    val firewallManager = com.minime.agent.server.modules.FirewallManager()
    val processManager = com.minime.agent.server.modules.ProcessLifecycleManager()
    val portPool = com.minime.agent.server.modules.PortPoolManager(firewall = firewallManager)
    val runner = AgentRunner(
        store, workspaceRoot, scope,
        settingsRepository = settingsRepository,
        firewallManager = firewallManager,
        processManager = processManager,
        portPool = portPool,
        terminalTool = com.minime.agent.server.modules.ServerTerminalTool(workspaceRoot, processManager, portPool)
    )
    // 进程退出（kill / TTL 回收 / 自然退出）→ 自动归还其占用的端口。
    processManager.onExit { id -> portPool.releaseByOwner(id) }
    // 环境数据访问：与工具链共用同一套容器文件系统/skill 仓库，供 App 侧边栏按目标读取。
    val fileAccess = LocalFileAccessProvider(workspacePath = workspaceRoot)
    val skillRepository = ServerSkillRepository(fileAccess)

    // 崩溃续跑：服务重启后恢复未完成任务（App 删后台/进程被杀不影响云端执行）
    runner.resumeUnfinished()

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        configureServer(
            store, runner, token, fileAccess, skillRepository,
            settingsRepository = settingsRepository,
            firewallManager = firewallManager,
            processManager = processManager,
            portPool = portPool
        )
    }.start(wait = true)
}
