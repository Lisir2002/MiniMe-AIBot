package com.minime.code.feature.agent.domain.container

import com.minime.code.core.util.BoundedLineReader
import com.minime.code.core.util.FileLogger
import com.minime.code.core.util.LINE_TRUNCATED_NOTE
import com.minime.code.feature.agent.domain.container.CommandEngine
import com.minime.code.feature.agent.domain.tool.explorer.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "RemoteSshEngine"

/**
 * [CommandEngine] 的远程 SSH 实现：用 sshj exec channel 在远程服务器上执行命令。
 *
 * 共享一个 [RemoteSshConnection]（持有 sshj [SSHClient]）执行命令；文件读写由 [RemoteSftpFileAccess]
 * 走另一条独立的 SFTP transport（见 [RemoteSshConnection.sftp]），两者隔离互不影响。
 *
 * 与 [LinuxContainerEngine] 的语义对应：
 * - [ensureInstalled]：建立/维持 SSH 连接（对应本地解压 rootfs）；
 * - [isContainerInstalled]：SSH 连接是否存活（对应本地 rootfs 是否就绪）；
 * - [isProvisioned]：恒 true（远程工具由用户自行保证，对应本地 apk 装包完成）；
 * - [defaultShell]：/bin/bash（远程服务器通常有 bash）。
 */
class RemoteSshEngine @Inject constructor(
    private val connection: RemoteSshConnection
) : CommandEngine {

    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    private val connectMutex = Mutex()

    /**
     * 云端沙箱容器名（从服务器 ~/.minimecode/sandbox-mode.marker 探测）。
     * 非 null 表示当前 SSH 服务器已通过配置向导装好 AI 沙箱容器，AI 命令应经 docker exec 进入容器执行；
     * null 表示旧版纯 SSH 连接（用户手动添加），命令直接在宿主机执行，保持向后兼容。
     * 每次连接建立后探测一次，容器失活时自动清空降级。
     */
    @Volatile private var sandboxContainer: String? = null
    @Volatile private var sandboxChecked: Boolean = false

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        ensureInstalled()
        emitAll(streamExec(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)

    private fun streamExec(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(远程流式) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val session = connection.startExecSession(buildCdCommand(command, projectPath))
        val timedOut = AtomicBoolean(false)
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = watchScope.launch {
            delay(effectiveTimeout)
            if (session.isOpen) {
                timedOut.set(true)
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: ${sanitizeCommandForLog(command)}")
                runCatching { session.close() }
            }
        }
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException && session.isOpen) {
                FileLogger.i(TAG, "命令被取消，关闭 session: ${sanitizeCommandForLog(command)}")
                runCatching { session.close() }
            }
        }
        val reader = BoundedLineReader(InputStreamReader(session.inputStream))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                emit(CommandEvent.Line(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text))
            }
            val exitCode = session.exitStatus
            watchdog.cancel()
            if (timedOut.get()) {
                emit(CommandEvent.Line("[命令执行超时：超过 ${effectiveTimeout}ms 已被强制终止]"))
                emit(CommandEvent.Exit(null))
            } else {
                if (exitCode != 0) FileLogger.w(TAG, "命令退出码=$exitCode: ${sanitizeCommandForLog(command)}")
                else FileLogger.v(TAG, "命令完成(退出码 0): ${sanitizeCommandForLog(command)}")
                emit(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            watchdog.cancel()
            if (timedOut.get()) {
                emit(CommandEvent.Line("[命令执行超时：超过 ${effectiveTimeout}ms 已被强制终止]"))
                emit(CommandEvent.Exit(null))
            } else {
                FileLogger.e(TAG, "命令读输出异常(已保留此前输出): ${sanitizeCommandForLog(command)}", e)
                emit(CommandEvent.Line("[命令执行异常：${e.message}]"))
                emit(CommandEvent.Exit(null))
            }
        } finally {
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            runCatching { reader.close() }
            runCatching { session.close() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs).output
    }

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs)
    }

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!isContainerInstalled()) return null
        return runCatching { execCaptured(command, projectPath, timeoutMs) }
            .getOrElse {
                FileLogger.w(TAG, "远程命令执行失败(连接可能已断): ${sanitizeCommandForLog(command)}", it)
                null
            }
    }

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs, unbounded = true)
    }

    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
        unbounded: Boolean = false
    ): CommandResult = withContext(Dispatchers.IO) {
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(远程同步) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val session = connection.startExecSession(buildCdCommand(command, projectPath))
        val output = if (unbounded) BoundedOutput.hardCapped(MAX_UNBOUNDED_CHARS) else BoundedOutput()
        var exitCode: Int? = null
        try {
            coroutineScope {
                val watchdog = launch {
                    delay(effectiveTimeout)
                    if (session.isOpen) {
                        FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: ${sanitizeCommandForLog(command)}")
                        runCatching { session.close() }
                    }
                }
                val reader = BoundedLineReader(InputStreamReader(session.inputStream))
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        output.append(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text)
                        output.append("\n")
                    }
                } finally {
                    watchdog.cancel()
                    runCatching { reader.close() }
                }
                // 并发读 stderr 合并进 output：本地引擎 redirectErrorStream(true) 是合并语义，
                // 远程若不合并，命令报错（如 rg 未安装时的 command not found）只写 stderr 会被静默丢弃。
                val stderrJob = launch {
                    val errReader = BoundedLineReader(InputStreamReader(session.errorStream))
                    try {
                        while (true) {
                            val errLine = errReader.readLine() ?: break
                            output.append(if (errLine.truncated) "${errLine.text}\n$LINE_TRUNCATED_NOTE" else errLine.text)
                            output.append("\n")
                        }
                    } finally {
                        runCatching { errReader.close() }
                    }
                }
                stderrJob.join()
                // sshj 的 exitStatus 在流 EOF 后未必就绪，close 后才保证有值
                runCatching { session.close() }
                exitCode = session.exitStatus
            }
        } finally {
            runCatching { session.close() }
        }
        FileLogger.v(TAG, "命令完成(远程, 退出码 $exitCode，输出 ${output.totalChars} 字符): ${sanitizeCommandForLog(command)}")
        CommandResult(output.build(), exitCode, output.truncated)
    }

    override fun isContainerInstalled(): Boolean = connection.isConnected()

    override fun isProvisioned(): Boolean = true

    override fun defaultShell(): String = "/bin/bash"

    override suspend fun ensureInstalled() = connectMutex.withLock {
        if (!connection.isConnected()) {
            _initProgress.value = ContainerInitState.InstallingPackages(line = "正在连接 SSH 服务器…")
            try {
                connection.connect()
            } catch (e: Exception) {
                FileLogger.e(TAG, "SSH 连接失败", e)
                val friendly = friendlySshError(e)
                _initProgress.value = ContainerInitState.Failed(friendly)
                throw RuntimeException(friendly, e)
            }
        }
        // 连接就绪后探测云端沙箱标记（每次连接只探一次）
        if (!sandboxChecked) detectSandbox()
        _initProgress.value = ContainerInitState.Ready
    }

    /**
     * 探测服务器是否配置了 AI 沙箱容器：
     * 读 ~/.minimecode/sandbox-mode.marker 的 container= 值，并确认该容器仍在运行。
     * 标记不存在 / 容器已失活 → sandboxContainer=null（宿主机模式）。
     * 探测失败一律静默降级，绝不阻断命令执行。
     */
    private fun detectSandbox() {
        sandboxChecked = true
        if (!connection.isConnected()) return
        val probe = "m=\$HOME/.minimecode/sandbox-mode.marker; " +
            "[ -f \"\$m\" ] && c=\$(sed -n 's/^container=//p' \"\$m\" | head -1) || c=; " +
            "if [ -n \"\$c\" ] && command -v docker >/dev/null 2>&1; then " +
            "docker inspect -f '{{.State.Running}}' \"\$c\" 2>/dev/null | grep -q true && echo \"SANDBOX=\$c\" || echo 'SANDBOX='; " +
            "else echo 'SANDBOX='; fi"
        runCatching {
            val session = connection.startExecSession(probe)
            val out = StringBuilder()
            BufferedReader(InputStreamReader(session.inputStream)).use { r ->
                while (true) { val l = r.readLine() ?: break; out.append(l).append('\n') }
            }
            runCatching { session.close() }
            val value = out.lines().firstOrNull { it.startsWith("SANDBOX=") }
                ?.removePrefix("SANDBOX=")?.trim()
            sandboxContainer = value?.takeIf { it.isNotEmpty() }
            FileLogger.i(TAG, "云端沙箱探测: ${sandboxContainer ?: "未配置（宿主机模式）"}")
        }.onFailure {
            sandboxContainer = null
            FileLogger.w(TAG, "云端沙箱探测失败，降级宿主机模式", it)
        }
    }

    /** 拼接 cd 到 projectPath 再执行 command 的完整命令；projectPath 为 null 则直接执行。
     *  优先 cd 到 ~/workspace（符号链接），让 AI 执行 pwd 时看到 ~/workspace 而非真实路径。
     *  注入 GIT_CONFIG_GLOBAL 指向 App 管理的 ~/.minimecode/gitconfig。
     *  若当前服务器配置了云端沙箱容器，则整条命令经 `docker exec` 进入容器执行
     *  （容器内 ~/workspace→/workspace 符号链接、~/.minimecode 为 bind mount），实现与本地 proot 一致的隔离执行。 */
    private fun buildCdCommand(command: String, projectPath: String?): String {
        val container = sandboxContainer
        // 进容器后的工作目录切换 + git 配置注入（在容器内 HOME=/root 下生效）
        val inner = buildString {
            append("export GIT_CONFIG_GLOBAL=\"\$HOME/.minimecode/gitconfig\"; ")
            if (projectPath != null) {
                append("cd ~/workspace 2>/dev/null || cd ${shellQuote(projectPath)} 2>/dev/null; ")
            }
            append(command)
        }
        return if (container != null) {
            // docker exec 用 bash -lc 承载；shellQuote 处理任意引号，命令原样传入容器执行
            "docker exec -i ${shellQuote(container)} /bin/bash -lc ${shellQuote(inner)}"
        } else {
            // 旧版纯 SSH：宿主机直接执行（保持向后兼容）
            inner
        }
    }
}
