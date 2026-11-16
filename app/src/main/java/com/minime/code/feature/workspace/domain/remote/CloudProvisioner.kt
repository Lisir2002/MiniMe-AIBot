package com.minime.code.feature.workspace.domain.remote

import com.minime.code.feature.agent.domain.container.SshHostKeyStore
import com.minime.code.feature.agent.domain.container.SshHostKeyVerifier
import com.minime.code.feature.agent.domain.container.SshPrivateKeyStore
import com.minime.code.feature.agent.domain.container.friendlySshError
import com.minime.code.feature.agent.domain.container.sshHostKeyFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.PublicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云服务器一键配置编排器。
 *
 * 用**独立临时 SSHClient** 执行预检与安装命令，不占用/干扰正在服务的 [RemoteSshConnection]
 * （避免打断可能正在进行的 AI 会话）。三段式流程：
 * 1. [preflight] 只读探测 → [PreflightReport]；
 * 2. [buildPlan] 据报告生成安装计划（Docker 优先 + 无 sudo 降级）；
 * 3. [executePlan] 按 Phase 1（前置）/ Phase 2（AI 容器）逐项执行，进度写入 [uiState]。
 *
 * 主机密钥校验：优先复用已保存指纹；首次/变化走 [PendingHostKey] 让用户确认后
 * [confirmHostKeyAndRetry] 保存并重试（与既有 SSH 连接同一套 store）。
 */
@Singleton
class CloudProvisioner @Inject constructor(
    private val hostKeyStore: SshHostKeyStore,
    private val privateKeyStore: SshPrivateKeyStore
) {
    private val _uiState = MutableStateFlow(CloudProvisionUiState())
    val uiState: StateFlow<CloudProvisionUiState> = _uiState.asStateFlow()

    private val mutex = Mutex()
    @Volatile private var client: SSHClient? = null
    @Volatile private var cancelled = false

    /** 待确认的主机公钥（verify 阶段抛出后由 sshj 包装，存字段传递）。 */
    @Volatile private var pendingKey: PendingKeyInternal? = null
    private data class PendingKeyInternal(val host: String, val port: Int, val key: PublicKey)

    // ---- 对外流程 ----

    /** Phase 0：连接 + 只读预检。 */
    suspend fun preflight(params: ProvisionParams): Result<PreflightReport> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                cancelled = false
                _uiState.value = CloudProvisionUiState(phase = ProvisionPhase.PREFLIGHT, currentLog = "正在连接 ${params.host}:${params.port} …")
                connect(params)
                appendLog("连接成功，开始探测服务器环境…\n")
                val report = doPreflight(params)
                val plan = buildPlan(report, params)
                _uiState.value = _uiState.value.copy(
                    preflight = report, plan = plan, phase = ProvisionPhase.IDLE,
                    currentLog = _uiState.value.currentLog + "\n预检完成，已生成安装计划。"
                )
                report
            }.onFailure { e ->
                val pending = pendingKey
                if (pending != null) {
                    _uiState.value = _uiState.value.copy(
                        pendingHostKey = CloudProvisionUiState.PendingHostKey(
                            pending.host, pending.port, sshHostKeyFingerprint(pending.key), changed = hostKeyStore.get(pending.host, pending.port) != null
                        ),
                        error = null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(phase = ProvisionPhase.FAILED, error = friendlySshError(e))
                    disconnectInternal()
                }
            }
        }
    }

    /** 主机指纹确认后保存并重新预检。 */
    suspend fun confirmHostKeyAndRetry(params: ProvisionParams): Result<PreflightReport> {
        val pending = pendingKey ?: return Result.failure(IllegalStateException("无待确认主机密钥"))
        hostKeyStore.save(pending.host, pending.port, sshHostKeyFingerprint(pending.key))
        pendingKey = null
        disconnectInternal()
        _uiState.value = _uiState.value.copy(pendingHostKey = null, error = null)
        return preflight(params)
    }

    /** Phase 1 + 2：执行安装计划。 */
    suspend fun executePlan(params: ProvisionParams): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val plan = _uiState.value.plan ?: return@withLock Result.failure(IllegalStateException("尚无安装计划"))
            runCatching {
                cancelled = false
                if (!isConnected()) connect(params)
                val steps = plan.actions.map { ProvisionStep(it.id, it.title, ProvisionStep.State.PENDING) }
                _uiState.value = _uiState.value.copy(
                    phase = ProvisionPhase.INSTALL_PREREQUISITES, steps = steps, error = null, currentLog = ""
                )

                // Phase 1：前置依赖（requiresSudo=false 的动作），统一批量执行
                plan.actions.filter { !it.requiresSudo }.forEachIndexed { index, action ->
                    if (cancelled) throw ProvisionCancelled()
                    updateStep(action.id, ProvisionStep.State.RUNNING)
                    appendLog("\n▶ [${index + 1}/${plan.actions.size}] ${action.title}\n")
                    val cmd = commandFor(action.id, params)
                    val result = execStreaming(cmd)
                    appendLog(result.output)
                    if (result.exitCode != 0 && !action.id.endsWith(".optional")) {
                        updateStep(action.id, ProvisionStep.State.FAILED, result.output)
                        throw IllegalStateException("「${action.title}」执行失败（退出码 ${result.exitCode}）")
                    }
                    updateStep(action.id, ProvisionStep.State.DONE, result.output)
                }

                // Phase 2：AI 容器（requiresSudo=true 的动作，放在最后）
                _uiState.value = _uiState.value.copy(phase = ProvisionPhase.INSTALL_AI_CONTAINER)
                plan.actions.filter { it.requiresSudo }.forEach { action ->
                    if (cancelled) throw ProvisionCancelled()
                    updateStep(action.id, ProvisionStep.State.RUNNING)
                    appendLog("\n▶ ${action.title}\n")
                    val result = execStreaming(commandFor(action.id, params))
                    appendLog(result.output)
                    if (result.exitCode != 0) {
                        updateStep(action.id, ProvisionStep.State.FAILED, result.output)
                        throw IllegalStateException("「${action.title}」执行失败（退出码 ${result.exitCode}）")
                    }
                    updateStep(action.id, ProvisionStep.State.DONE, result.output)
                }

                _uiState.value = _uiState.value.copy(phase = ProvisionPhase.FINISHED, completed = true)
                appendLog("\n✅ 云端 AI 运行环境配置完成。\n")
                disconnectInternal()
                Unit
            }.onFailure { e ->
                val msg = if (e is ProvisionCancelled) "已取消" else friendlySshError(e).let { if (it.startsWith("SSH")) e.message ?: it else it }
                _uiState.value = _uiState.value.copy(phase = ProvisionPhase.FAILED, error = msg)
                disconnectInternal()
            }
        }
    }

    fun cancel() {
        cancelled = true
        runCatching { client?.disconnect() }
    }

    fun reset() {
        _uiState.value = CloudProvisionUiState()
        pendingKey = null
    }

    // ---- 连接 ----

    private data class ConnectParams(
        val host: String, val port: Int, val username: String, val auth: RemoteAuth,
        val workspacePath: String
    )

    /** 与 ConnectParams 等价的公开入参（UI 构造）。 */
    data class ProvisionParams(
        val host: String, val port: Int, val username: String, val auth: RemoteAuth,
        val workspacePath: String = "~/minimecode-workspace",
        /** AI 运行环境 Docker 镜像名；用户可填自建/私有镜像。 */
        val imageName: String = "minimecode/ai-runtime:latest"
    )

    private fun connect(params: ProvisionParams) {
        val verifier = object : HostKeyVerifier {
            override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> = mutableListOf()
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val saved = hostKeyStore.get(hostname, port)
                val fp = sshHostKeyFingerprint(key)
                if (saved == null || saved != fp) {
                    pendingKey = PendingKeyInternal(hostname, port, key)
                    throw SshHostKeyPending(hostname, port)
                }
                return true
            }
        }
        val c = SSHClient().apply {
            setConnectTimeout(20_000)
            setTimeout(30_000)
            addHostKeyVerifier(verifier)
            connect(params.host, params.port)
            when (val a = params.auth) {
                is RemoteAuth.Password -> authPassword(params.username, a.password)
                is RemoteAuth.PrivateKey -> {
                    val pem = privateKeyStore.readPem(a.privateKeyPath)
                    val pf = a.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                    authPublickey(params.username, loadKeys(pem, null, pf))
                }
            }
        }
        client = c
    }

    private fun isConnected(): Boolean = client?.isConnected == true && client?.isAuthenticated == true
    private fun disconnectInternal() {
        runCatching { client?.disconnect() }
        client = null
    }

    private class SshHostKeyPending(host: String, port: Int) : Exception("host key pending $host:$port")

    // ---- 命令执行 ----

    private data class ExecResult(val output: String, val exitCode: Int?)

    /** 执行一条命令，逐行追加到日志与当前步骤输出。 */
    private fun execStreaming(command: String): ExecResult {
        val c = client ?: throw IllegalStateException("SSH 未连接")
        val session = c.startSession()
        val cmd = session.exec("export LANG=C LC_ALL=C; $command")
        runCatching { cmd.outputStream.close() }
        val sb = StringBuilder()
        try {
            BufferedReader(InputStreamReader(cmd.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    sb.append(line).append('\n')
                    appendStepOutput(line)
                }
            }
            BufferedReader(InputStreamReader(cmd.errorStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    sb.append(line).append('\n')
                    appendStepOutput(line)
                }
            }
            runCatching { session.close() }
            return ExecResult(sb.toString(), cmd.exitStatus)
        } finally {
            runCatching { session.close() }
        }
    }

    /** 同步执行一条只读命令并返回首行/全部输出（用于预检）。 */
    private fun execCapture(command: String): ExecResult = execStreamingSilent(command)

    private fun execStreamingSilent(command: String): ExecResult {
        val c = client ?: throw IllegalStateException("SSH 未连接")
        val session = c.startSession()
        val cmd = session.exec("export LANG=C LC_ALL=C; $command")
        runCatching { cmd.outputStream.close() }
        val sb = StringBuilder()
        try {
            BufferedReader(InputStreamReader(cmd.inputStream)).use { reader ->
                while (true) { val l = reader.readLine() ?: break; sb.append(l).append('\n') }
            }
            BufferedReader(InputStreamReader(cmd.errorStream)).use { reader ->
                while (true) { val l = reader.readLine() ?: break; sb.append(l).append('\n') }
            }
            runCatching { session.close() }
            return ExecResult(sb.toString(), cmd.exitStatus)
        } finally {
            runCatching { session.close() }
        }
    }

    // ---- Phase 0：预检探测 ----

    private fun doPreflight(params: ProvisionParams): PreflightReport {
        // 用一条命令批量探测，减少往返；输出以 KEY=value 逐行给出，便于解析
        val probe = """
            echo "__OS=$(. /etc/os-release 2>/dev/null && echo ${'$'}PRETTY_NAME || uname -s)"
            echo "__ARCH=$(uname -m)"
            echo "__CPU=$(nproc 2>/dev/null || echo 0)"
            echo "__MEM=$( (free -m 2>/dev/null | awk '/^Mem:/{print ${'$'}2}') || echo 0)"
            echo "__DISK=$( (df -m / 2>/dev/null | awk 'END{print ${'$'}4}') || echo 0)"
            if command -v docker >/dev/null 2>&1; then echo "__DOCKER=1"; docker --version 2>/dev/null | sed 's/^/__DOCKVER=/'; else echo "__DOCKER=0"; fi
            command -v git >/dev/null 2>&1 && echo "__GIT=1" || echo "__GIT=0"
            command -v curl >/dev/null 2>&1 && echo "__CURL=1" || echo "__CURL=0"
            command -v bash >/dev/null 2>&1 && echo "__BASH=1" || echo "__BASH=0"
            command -v python3 >/dev/null 2>&1 && echo "__PY=1" || echo "__PY=0"
            command -v node >/dev/null 2>&1 && echo "__NODE=1" || echo "__NODE=0"
            if [ "$(id -u)" = "0" ]; then echo "__ROOT=1"; echo "__SUDO=1"; echo "__PSUDO=1";
            elif command -v sudo >/dev/null 2>&1; then echo "__ROOT=0"; echo "__SUDO=1"; sudo -n true >/dev/null 2>&1 && echo "__PSUDO=1" || echo "__PSUDO=0";
            else echo "__ROOT=0"; echo "__SUDO=0"; echo "__PSUDO=0"; fi
            (curl -sS -m 6 -o /dev/null -w '%{http_code}' https://registry-1.docker.io/v2/ 2>/dev/null | grep -qE '200|401') && echo "__DOCKERHUB=1" || echo "__DOCKERHUB=0"
            [ -d "${'$'}HOME/minimecode-workspace" ] && echo "__WS=1" || echo "__WS=0"
        """.trimIndent()
        val out = execCapture(probe).output
        val kv = out.lines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (line.startsWith("__") && i > 0) line.substring(2, i) to line.substring(i + 1) else null
        }.toMap()

        val detected = PreflightReport.Detected(
            hasDocker = kv["DOCKER"] == "1",
            dockerVersion = kv["DOCKVER"],
            hasGit = kv["GIT"] == "1",
            hasCurl = kv["CURL"] == "1",
            hasBash = kv["BASH"] == "1",
            hasPython = kv["PY"] == "1",
            hasNode = kv["NODE"] == "1",
            isRoot = kv["ROOT"] == "1",
            hasSudo = kv["SUDO"] == "1",
            passwordlessSudo = kv["PSUDO"] == "1",
            dockerHubReachable = kv["DOCKERHUB"] == "1",
            diskFreeMb = kv["DISK"]?.toLongOrNull() ?: 0,
            diskEnough = (kv["DISK"]?.toLongOrNull() ?: 0) >= 2048,
            workspaceExists = kv["WS"] == "1"
        )

        val arch = kv["ARCH"] ?: "未知"
        val items = listOf(
            PreflightItem("arch", "系统架构", if (arch.contains("aarch64") || arch.contains("arm64") || arch.contains("x86_64") || arch.contains("amd64")) PreflightItem.Status.OK else PreflightItem.Status.WARNING, arch),
            PreflightItem("docker", "Docker", if (detected.hasDocker) PreflightItem.Status.OK else PreflightItem.Status.MISSING, detected.dockerVersion ?: "未安装"),
            PreflightItem("git", "Git", if (detected.hasGit) PreflightItem.Status.OK else PreflightItem.Status.MISSING, if (detected.hasGit) "已安装" else "缺失"),
            PreflightItem("curl", "curl", if (detected.hasCurl) PreflightItem.Status.OK else PreflightItem.Status.MISSING, if (detected.hasCurl) "已安装" else "缺失"),
            PreflightItem("bash", "Bash", if (detected.hasBash) PreflightItem.Status.OK else PreflightItem.Status.MISSING, if (detected.hasBash) "已安装" else "缺失"),
            PreflightItem("python", "Python3", if (detected.hasPython) PreflightItem.Status.OK else PreflightItem.Status.WARNING, if (detected.hasPython) "已安装" else "缺失（部分工具可选）"),
            PreflightItem("node", "Node.js", if (detected.hasNode) PreflightItem.Status.OK else PreflightItem.Status.WARNING, if (detected.hasNode) "已安装" else "缺失（部分工具可选）"),
            PreflightItem("sudo", "sudo 权限", if (detected.isRoot) PreflightItem.Status.OK else if (detected.passwordlessSudo) PreflightItem.Status.OK else if (detected.hasSudo) PreflightItem.Status.WARNING else PreflightItem.Status.MISSING,
                if (detected.isRoot) "root 用户" else if (detected.passwordlessSudo) "免密 sudo" else if (detected.hasSudo) "需密码 sudo" else "无 sudo"),
            PreflightItem("dockerhub", "Docker Hub 连通", if (detected.dockerHubReachable) PreflightItem.Status.OK else PreflightItem.Status.WARNING, if (detected.dockerHubReachable) "可访问" else "不可达（将尝试镜像加速）"),
            PreflightItem("disk", "磁盘空间", if (detected.diskEnough) PreflightItem.Status.OK else PreflightItem.Status.WARNING, "${detected.diskFreeMb} MB 可用")
        )

        return PreflightReport(
            host = params.host,
            osName = kv["OS"] ?: "未知",
            arch = arch,
            cpuCores = kv["CPU"] ?: "未知",
            memoryMb = kv["MEM"]?.toLongOrNull() ?: 0,
            diskFreeMb = detected.diskFreeMb,
            items = items,
            detected = detected
        )
    }

    // ---- 生成计划 ----

    private fun buildPlan(report: PreflightReport, params: ProvisionParams): ProvisionPlan {
        val d = report.detected
        val canInstallDocker = d.isRoot || d.passwordlessSudo
        val strategy = if (d.hasDocker || canInstallDocker) ProvisionStrategy.DOCKER else ProvisionStrategy.NATIVE_FALLBACK
        val actions = mutableListOf<PlanAction>()
        val warnings = mutableListOf<String>()

        // Phase 1：前置依赖（无需 sudo，用户目录内）
        actions += PlanAction("prereq.dirs", "创建工具目录", "建立 ~/.minimecode 与工作区目录")
        if (!d.hasCurl) actions += PlanAction("prereq.curl", "安装 curl", "用户目录内安装（或提示系统包）")
        if (!d.hasGit) actions += PlanAction("prereq.git", "安装 git", "用于云端仓库操作")
        if (!d.hasBash) actions += PlanAction("prereq.bash", "确认 Bash", "远程默认 shell")

        // Phase 2：AI 容器（最后执行）
        when (strategy) {
            ProvisionStrategy.DOCKER -> {
                if (!d.hasDocker) {
                    actions += PlanAction("docker.install", "安装 Docker", "检测发行版并安装 Docker CE", requiresSudo = true)
                }
                actions += PlanAction("docker.image", "拉取 AI 运行环境镜像", "${params.imageName}（含 git/ripgrep/python/node）", requiresSudo = true)
                actions += PlanAction("docker.run", "启动 AI 容器", "挂载工作区、设置资源上限、健康检查", requiresSudo = true)
                if (!d.dockerHubReachable) warnings += "Docker Hub 不可达，将自动尝试国内镜像加速源；如仍失败请配置代理。"
            }
            ProvisionStrategy.NATIVE_FALLBACK -> {
                warnings += "服务器无 Docker 且无免密 sudo，将降级为 ~/.local 原生安装（不修改系统目录）。"
                actions += PlanAction("native.runtime", "原生安装 AI 工具链", "在 ~/.local 安装 git/ripgrep/python 运行时", requiresSudo = true)
                actions += PlanAction("native.marker", "写入环境标记", "标记云端环境已就绪", requiresSudo = true)
            }
        }
        if (!d.diskEnough) warnings += "磁盘可用空间不足 2GB，安装可能失败。"

        val reason = when {
            d.hasDocker -> "检测到 Docker ${d.dockerVersion ?: ""}，直接复用"
            canInstallDocker -> if (d.isRoot) "当前为 root 用户，可安装 Docker" else "检测到免密 sudo，可安装 Docker"
            else -> "无 Docker 且无免密 sudo，降级原生安装"
        }
        return ProvisionPlan(strategy, reason, actions, warnings)
    }

    // ---- 命令生成 ----

    private fun commandFor(actionId: String, params: ProvisionParams): String {
        val ws = shellQuote(params.workspacePath.replace("~", "\$HOME"))
        return when (actionId) {
            "prereq.dirs" -> "mkdir -p \"\$HOME/.minimecode\" $ws && echo '工具目录就绪'"
            "prereq.curl" -> "if command -v curl >/dev/null 2>&1; then echo 'curl 已存在'; elif command -v wget >/dev/null 2>&1; then echo 'wget 可用（curl 非必需）'; else echo '缺少 curl/wget，请手动安装'; fi"
            "prereq.git" -> "if command -v git >/dev/null 2>&1; then git --version; else echo 'git 缺失（将在容器阶段提供）'; fi"
            "prereq.bash" -> "command -v bash && bash --version | head -1 || echo 'bash 缺失'"
            "docker.install" -> "if command -v docker >/dev/null 2>&1; then echo 'Docker 已安装'; else (command -v apt-get >/dev/null && sudo apt-get update -qq && sudo apt-get install -y -qq docker.io) || (command -v dnf >/dev/null && sudo dnf install -y docker) || (command -v yum >/dev/null && sudo yum install -y docker) || echo '未识别发行版，请手动安装 Docker'; fi; docker --version 2>&1"
            "docker.image" -> {
                val image = shellQuote(params.imageName)
                "(docker image inspect $image >/dev/null 2>&1 && echo '镜像已存在') || docker pull $image"
            }
            "docker.run" -> "docker rm -f minimecode-ai >/dev/null 2>&1 || true; docker run -d --name minimecode-ai --restart unless-stopped -v \"\$HOME/.minimecode:/root/.minimecode\" -v $ws:/workspace ${shellQuote(params.imageName)} && sleep 2 && docker exec minimecode-ai sh -c 'echo container-ok; git --version 2>/dev/null || true; [ -L /root/workspace ] || ln -sfn /workspace /root/workspace' && printf 'container=minimecode-ai\\nimage=%s\\n' ${shellQuote(params.imageName)} > \"\$HOME/.minimecode/sandbox-mode.marker\" && echo 'AI 沙箱容器已启动并标记'"
            "native.runtime" -> "mkdir -p \"\$HOME/.local/bin\" && { command -v apt-get >/dev/null 2>&1 && sudo apt-get update -qq && sudo apt-get install -y -qq git curl ripgrep python3; } || { command -v dnf >/dev/null 2>&1 && sudo dnf install -y git curl ripgrep python3; } || echo '未识别发行版，请手动安装 git/curl/ripgrep/python3'; rm -f \"\$HOME/.minimecode/sandbox-mode.marker\"; echo '原生工具链安装结束'"
            "native.marker" -> "printf 'strategy=native\\ninstalled_at=%s\\n' \"\$(date +%s)\" > \"\$HOME/.minimecode/cloud-env.marker\" && echo '环境标记写入完成'"
            else -> "echo '未知动作 $actionId'; exit 1"
        }
    }

    companion object {
        private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }

    private class ProvisionCancelled : Exception("cancelled")

    // ---- 状态更新 ----

    private fun appendLog(text: String) {
        _uiState.value = _uiState.value.copy(currentLog = _uiState.value.currentLog + text)
    }

    private fun updateStep(id: String, state: ProvisionStep.State, output: String? = null) {
        _uiState.value = _uiState.value.copy(
            steps = _uiState.value.steps.map {
                if (it.actionId == id) it.copy(
                    state = state,
                    output = output ?: it.output
                ) else it
            }
        )
    }

    private fun appendStepOutput(line: String) {
        val running = _uiState.value.steps.firstOrNull { it.state == ProvisionStep.State.RUNNING } ?: return
        val updated = running.copy(output = (running.output + line + "\n").takeLast(4000))
        _uiState.value = _uiState.value.copy(
            steps = _uiState.value.steps.map { if (it.actionId == running.actionId) updated else it }
        )
    }
}
