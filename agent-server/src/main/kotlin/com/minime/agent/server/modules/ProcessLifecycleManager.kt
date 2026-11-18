package com.minime.agent.server.modules

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 容器进程生命周期管理器：确保"除用户显式持久化外，任何后台进程都有生命周期"，
 * 到期自动回收释放内存，避免编译/服务/残留进程越堆越多吃满容器内存。
 *
 * 设计要点（避免"短周期反面作用"）：
 * - 分级 TTL：编译/构建类命令给长 TTL（默认 2h），常驻服务更长（默认 6h），普通后台进程 30min；
 * - 心跳续期：terminal 的 read/send 会刷新心跳，正在交互/消费的进程不因静止被误杀；
 * - 活跃产出续期：回收前检查进程仍在产生输出则续一个周期；
 * - 持久化例外：用户/AI 标记 persistent 的进程永不自动回收（仍需可手动 kill）；
 * - 优雅退出：先 SIGTERM（Process.destroy），宽限期后 SIGKILL（destroyForcibly）；
 * - 内存记账：登记时记录，回收时读 /proc/<pid>/status 的 VmRSS 估算释放量，写日志。
 *
 * 进程来源：terminal 工具启动的后台会话（主）、Shizuku/Bash 中显式登记的长任务。
 * 一次性同步命令（curl、ls 等）执行完即退出，不进入注册表。
 */
class ProcessLifecycleManager {
    private val log = LoggerFactory.getLogger(ProcessLifecycleManager::class.java)

    /**
     * 进程退出回调：任何进程被手动 kill / TTL 回收 / 自然退出时触发，入参为进程 id。
     * 端口池借此在进程消亡时自动归还其占用的端口，避免端口泄漏。
     */
    @Volatile
    private var onExit: ((id: String) -> Unit)? = null
    fun onExit(handler: (id: String) -> Unit) { onExit = handler }
    private fun fireExit(id: String) = runCatching { onExit?.invoke(id) }
        .onFailure { log.warn("进程退出回调异常 id={}: {}", id, it.message) }

    data class Record(
        val id: String,
        val pid: Long,
        val command: String,
        val label: String,
        val kind: String,
        val startedAt: Long = System.currentTimeMillis(),
        @Volatile var lastHeartbeat: Long = System.currentTimeMillis(),
        @Volatile var ttlMs: Long,
        @Volatile var persistent: Boolean = false,
        @Volatile var exitOnIdle: Boolean = true,
        /** 进程句柄，仅服务端内存持有，不序列化。 */
        val process: Process? = null,
        /** 最近一次观测到的输出字节数，用于判断"仍在产出"。 */
        @Volatile var lastOutputSize: Long = 0,
        @Volatile var lastOutputCheckAt: Long = System.currentTimeMillis()
    )

    private val records = ConcurrentHashMap<String, Record>()
    private val seq = AtomicLong(0)

    companion object {
        const val KIND_TERMINAL = "terminal"
        const val KIND_COMMAND = "command"
        const val KIND_SERVICE = "service"

        const val TTL_SHORT_MS = 30L * 60 * 1000      // 30 分钟：普通后台进程
        const val TTL_BUILD_MS = 2L * 60 * 60 * 1000  // 2 小时：编译/构建
        const val TTL_SERVICE_MS = 6L * 60 * 60 * 1000 // 6 小时：常驻服务

        /** 编译/构建关键词：命中则给长 TTL，避免"编译到一半被杀"的反面作用。 */
        private val BUILD_PATTERN = Regex(
            "(?i)(gradle|mvn|maven|\\bcmake\\b|\\bmake\\b|\\bninja\\b|\\bgo build\\b|\\bcargo build\\b|" +
                "\\bnpm (run )?(build|install)\\b|\\byarn build\\b|\\bpnpm build\\b|\\bflutter build\\b|" +
                "\\bxcodebuild\\b|\\bbazel build\\b|\\bcompile\\b|\\b编译\\b|assembleDebug|assembleRelease)"
        )
        /** 常驻服务关键词：给最长 TTL。 */
        private val SERVICE_PATTERN = Regex(
            "(?i)(--serve|serve\\b|server\\b|\\bwatch\\b|--watch|dev\\b|start-server|\\bdaemon\\b|\\bserve\\b|uvicorn|gunicorn)"
        )

        fun classifyTtl(command: String): Long = when {
            SERVICE_PATTERN.containsMatchIn(command) -> TTL_SERVICE_MS
            BUILD_PATTERN.containsMatchIn(command) -> TTL_BUILD_MS
            else -> TTL_SHORT_MS
        }
    }

    /** 登记一个后台进程；相同 id 重复登记会覆盖旧记录（不杀旧进程，调用方负责）。 */
    fun register(
        id: String,
        pid: Long,
        command: String,
        label: String = id,
        kind: String = KIND_COMMAND,
        process: Process? = null,
        ttlMs: Long? = null
    ): Record {
        val record = Record(
            id = id, pid = pid, command = command, label = label, kind = kind,
            process = process, ttlMs = ttlMs ?: classifyTtl(command)
        )
        records[id] = record
        log.info("进程登记: id={} pid={} kind={} ttl={}min cmd={}", id, pid, kind, record.ttlMs / 60000, command.take(160))
        return record
    }

    fun heartbeat(id: String) {
        records[id]?.lastHeartbeat = System.currentTimeMillis()
    }

    /** 进程仍在产出输出时调用，用于回收前判定活跃度并续期。 */
    fun noteOutput(id: String, size: Long) {
        val now = System.currentTimeMillis()
        records[id]?.let {
            it.lastOutputSize = size
            it.lastOutputCheckAt = now
            it.lastHeartbeat = now
        }
    }

    fun setPersistent(id: String, persistent: Boolean): Boolean {
        val r = records[id] ?: return false
        r.persistent = persistent
        r.lastHeartbeat = System.currentTimeMillis()
        log.info("进程持久化标记: id={} persistent={}", id, persistent)
        return true
    }

    fun setTtl(id: String, ttlMs: Long): Boolean {
        val r = records[id] ?: return false
        r.ttlMs = ttlMs.coerceAtLeast(60_000L)
        return true
    }

    fun unregister(id: String): Record? = records.remove(id)

    fun list(): List<Record> = records.values.sortedBy { it.startedAt }

    fun get(id: String): Record? = records[id]

    /** 手动终止（无论是否 persistent）。 */
    fun kill(id: String, graceful: Boolean = true): KillOutcome {
        val r = records[id] ?: return KillOutcome.NotFound
        val alive = isAlive(r)
        if (!alive) {
            records.remove(id)
            fireExit(id)
            return KillOutcome.AlreadyExited
        }
        val freedKb = rssKb(r.pid)
        terminate(r.process, r.pid, graceful)
        records.remove(id)
        fireExit(id)
        log.info("进程手动终止: id={} pid={} 释放约 {}KB", id, r.pid, freedKb)
        return KillOutcome.Killed(freedKb)
    }

    sealed class KillOutcome {
        object NotFound : KillOutcome()
        object AlreadyExited : KillOutcome()
        data class Killed(val freedKb: Long) : KillOutcome()
    }

    /** 启动后台回收守护：定期扫描，回收"到期 + 非持久化 + 非活跃产出"的进程。 */
    fun startJanitor(scope: CoroutineScope, intervalMs: Long = 60_000L) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(intervalMs)
                runCatching { sweep() }.onFailure { log.warn("进程回收扫描异常: {}", it.message) }
            }
        }
        log.info("进程生命周期守护已启动，扫描间隔 {}ms", intervalMs)
    }

    /** 一次回收扫描，返回被回收记录摘要（供测试与状态查询）。 */
    fun sweep(): List<String> {
        val now = System.currentTimeMillis()
        val reaped = mutableListOf<String>()
        for (r in list()) {
            if (r.persistent) continue
            if (!isAlive(r)) {
                records.remove(r.id)
                fireExit(r.id)
                continue
            }
            val idleMs = now - r.lastHeartbeat
            if (idleMs < r.ttlMs) continue
            // 回收前再确认：进程是否仍在产出输出（最近 2 分钟内输出量增长视为活跃，续期跳过）。
            val currentOutput = outputSize(r.pid)
            if (currentOutput > r.lastOutputSize && now - r.lastOutputCheckAt < 120_000L) {
                r.lastOutputSize = currentOutput
                r.lastOutputCheckAt = now
                r.lastHeartbeat = now
                continue
            }
            val freedKb = rssKb(r.pid)
            terminate(r.process, r.pid, graceful = true)
            records.remove(r.id)
            fireExit(r.id)
            val summary = "回收 ${r.id}(pid=${r.pid}) idle=${idleMs / 60000}min 释放约 ${freedKb}KB cmd=${r.command.take(80)}"
            reaped += summary
            log.info(summary)
        }
        return reaped
    }

    private fun isAlive(r: Record): Boolean {
        r.process?.let { return it.isAlive }
        return r.pid > 0 && File("/proc/${r.pid}").exists()
    }

    private fun terminate(process: Process?, pid: Long, graceful: Boolean) {
        if (process != null) {
            try {
                if (graceful) {
                    process.destroy()
                    if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
                } else process.destroyForcibly()
                return
            } catch (e: Exception) {
                log.warn("Process.destroy 失败 pid={}: {}", pid, e.message)
            }
        }
        // 无句柄时按 pid 发信号（/proc 场景）。
        val sig = if (graceful) "TERM" else "KILL"
        runCatching { ProcessBuilder("/bin/sh", "-c", "kill -$sig $pid 2>/dev/null").start().waitFor() }
    }

    /** 读 /proc/<pid>/status 的 VmRSS（KB），失败返回 0。 */
    private fun rssKb(pid: Long): Long = runCatching {
        File("/proc/$pid/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0L
    }.getOrDefault(0L)

    private fun outputSize(pid: Long): Long = runCatching {
        File("/proc/$pid/fd/1").let { if (it.exists()) it.length() else 0L }
    }.getOrDefault(0L)
}
