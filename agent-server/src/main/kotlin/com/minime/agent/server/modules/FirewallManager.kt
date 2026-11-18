package com.minime.agent.server.modules

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 容器出口防火墙管理器：「可自由开放 / 关闭出站防火墙」。
 *
 * 真实拦截用 iptables 的 OUTPUT 链：
 * - disable（开放）：删除自定义链、OUTPUT 恢复 ACCEPT；
 * - enable（关闭/收紧）：新建 MINIME_FW 链，OUTPUT 跳转到它，默认 DROP，
 *   仅放行 loopback、已建立连接、DNS、以及用户白名单（host / CIDR / port）。
 *
 * 能力约束（重要）：Docker 默认 capability 不含 CAP_NET_ADMIN，容器内 iptables 无法改规则。
 * 因此：
 * - 能力可用时正常工作；
 * - 能力不可用时**不假装成功**：策略仍可保存，但返回明确降级提示，告知需在部署时
 *   给容器加 `--cap-add NET_ADMIN`（或 `--privileged`）并安装 iptables。
 *
 * 这样即使防火墙未真正生效，用户/AI 也能从 status 里看到真实状态，不会误以为已受保护。
 */
class FirewallManager(
    private val homePath: String = System.getenv("HOME") ?: "/root"
) {
    private val log = LoggerFactory.getLogger(FirewallManager::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val lock = Any()

    @Serializable
    data class Rule(
        val host: String = "",       // 域名或 IP / CIDR，空表示不限
        val port: Int = 0,           // 0 表示不限端口
        val protocol: String = "tcp" // tcp/udp/any
    )

    @Serializable
    data class State(
        val enabled: Boolean = false,
        /** 白名单：enable 时仅这些目标可出站（配合 loopback/DNS/ESTABLISHED 系统放行）。 */
        val allow: List<Rule> = listOf(),
        /** 黑名单：任意模式下都拒绝的目标（优先级高于 allow）。 */
        val deny: List<Rule> = listOf(),
        /** 上次操作是否真正生效到内核（无能力时为 false）。 */
        val kernelApplied: Boolean = false,
        val note: String = ""
    )

    private val stateFile: File get() = File(homePath, ".minimecode/firewall/state.json")

    @Volatile
    private var cached: State = load()

    private fun load(): State = runCatching {
        if (stateFile.isFile) json.decodeFromString<State>(stateFile.readText()) else State()
    }.getOrDefault(State())

    private fun save(state: State): State {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(json.encodeToString(state))
        cached = state
        return state
    }

    fun status(): State = synchronized(lock) {
        // 每次查询重新探测能力，反映真实状态（容器能力可能在重启后变化）。
        val usable = iptablesUsable()
        val s = cached
        if (s.enabled != usable || s.kernelApplied != usable) {
            val updated = s.copy(
                kernelApplied = usable,
                note = if (usable) s.note else "容器缺少 CAP_NET_ADMIN 或未安装 iptables，规则未生效（仅策略已保存）。"
            )
            save(updated)
            updated
        } else s
    }

    /** 探测 iptables 是否可用（既要有二进制，也要有 NET_ADMIN 能力）。 */
    fun iptablesUsable(): Boolean = runCatching {
        val p = ProcessBuilder("iptables", "-w", "-L", "-n").redirectErrorStream(true).start()
        val exit = p.waitFor()
        exit == 0
    }.getOrDefault(false)

    /** 开放防火墙：清空自定义链，OUTPUT 恢复默认放行。 */
    fun disable(): State = synchronized(lock) {
        val usable = iptablesUsable()
        var note = ""
        if (usable) {
            runCatching {
                exec("iptables", "-w", "-D", "OUTPUT", "-j", CHAIN) // 不存在则忽略
                exec("iptables", "-w", "-F", CHAIN)
                exec("iptables", "-w", "-X", CHAIN)
                note = "出站防火墙已关闭，所有出站连接放行。"
            }.onFailure { note = "关闭规则时出错: ${it.message}" }
        } else {
            note = "容器缺少 CAP_NET_ADMIN，无法操作内核防火墙；已将策略标记为开放。部署时请加 --cap-add NET_ADMIN 并安装 iptables。"
        }
        save(State(enabled = false, allow = cached.allow, deny = cached.deny, kernelApplied = false, note = note))
    }

    /** 收紧防火墙：白名单外默认拒绝。 */
    fun enable(): State = synchronized(lock) {
        val usable = iptablesUsable()
        var note: String
        if (usable) {
            // 幂等重建自定义链：先尝试删引用再清规则删链，忽略"不存在"错误，然后重建。
            runCatching { exec("iptables", "-w", "-D", "OUTPUT", "-j", CHAIN) }
            runCatching { exec("iptables", "-w", "-F", CHAIN) }
            runCatching { exec("iptables", "-w", "-X", CHAIN) }
            exec("iptables", "-w", "-N", CHAIN)

            val cmds = mutableListOf<List<String>>()
            // 系统必需放行：loopback、已建立连接、DNS。
            cmds += listOf("iptables", "-w", "-A", CHAIN, "-i", "lo", "-j", "ACCEPT")
            cmds += listOf("iptables", "-w", "-A", CHAIN, "-m", "state", "--state", "ESTABLISHED,RELATED", "-j", "ACCEPT")
            cmds += listOf("iptables", "-w", "-A", CHAIN, "-p", "udp", "--dport", "53", "-j", "ACCEPT")
            cmds += listOf("iptables", "-w", "-A", CHAIN, "-p", "tcp", "--dport", "53", "-j", "ACCEPT")
            // 黑名单优先 DROP，其次白名单 ACCEPT。
            cached.deny.forEach { cmds += dropRule(it) }
            cached.allow.forEach { cmds += acceptRule(it) }
            // 默认拒绝 + OUTPUT 首条跳转到自定义链。
            cmds += listOf("iptables", "-w", "-A", CHAIN, "-j", "DROP")
            cmds += listOf("iptables", "-w", "-I", "OUTPUT", "1", "-j", CHAIN)
            cmds.forEach { c -> exec(*c.toTypedArray()) }
            note = "出站防火墙已启用：默认拒绝，放行 loopback/DNS/已建立连接及 ${cached.allow.size} 条白名单规则。"
        } else {
            note = "容器缺少 CAP_NET_ADMIN，无法启用内核防火墙；策略已保存但未生效。部署时请加 --cap-add NET_ADMIN 并安装 iptables。"
        }
        save(State(enabled = true, allow = cached.allow, deny = cached.deny, kernelApplied = usable, note = note))
    }

    /**
     * 校验规则目标端口是否属于禁止操作的高危端口。
     * 返回 null 表示允许；否则返回可读的拒绝原因。
     *
     * 校验放在管理器层，保证无论从 Agent 工具还是 HTTP 端点调用都无法绕过。
     * port=0（不限端口）不针对具体端口，仍允许，用于「放行某域名全部端口」的常规用法。
     */
    fun validateRule(rule: Rule): String? =
        if (rule.port in PROTECTED_PORTS) {
            "端口 ${rule.port} 属于高危端口，禁止防火墙工具操作（涉及 SSH/数据库/管理面板/通用 Web 等关键通道）。"
        } else null

    /** 增删白名单/黑名单规则；若当前已启用，重新应用使改动即时生效。 */
    fun addRule(kind: String, rule: Rule): State = synchronized(lock) {
        validateRule(rule)?.let { reason ->
            return@synchronized save(cached.copy(note = "规则未添加：$reason"))
        }
        val allow = cached.allow.toMutableList()
        val deny = cached.deny.toMutableList()
        when (kind) {
            "allow" -> if (rule !in allow) allow += rule
            "deny" -> if (rule !in deny) deny += rule
        }
        save(cached.copy(allow = allow, deny = deny))
        if (cached.enabled) enable()
        status()
    }

    fun removeRule(kind: String, rule: Rule): State = synchronized(lock) {
        validateRule(rule)?.let { reason ->
            return@synchronized save(cached.copy(note = "规则未移除：$reason"))
        }
        val allow = cached.allow.toMutableList()
        val deny = cached.deny.toMutableList()
        when (kind) {
            "allow" -> allow.remove(rule)
            "deny" -> deny.remove(rule)
        }
        save(cached.copy(allow = allow, deny = deny))
        if (cached.enabled) enable()
        status()
    }

    fun reset(): State = synchronized(lock) {
        disable()
        save(State(note = "防火墙规则已重置为开放状态。"))
    }

    private fun acceptRule(r: Rule) = baseRule("ACCEPT", r)
    private fun dropRule(r: Rule) = baseRule("DROP", r)

    private fun baseRule(target: String, r: Rule): List<String> {
        val cmd = mutableListOf("iptables", "-w", "-A", CHAIN)
        if (r.host.isNotBlank()) cmd += listOf("-d", r.host)
        if (r.port > 0) {
            val proto = if (r.protocol.equals("any", ignoreCase = true)) "tcp" else r.protocol.lowercase()
            cmd += listOf("-p", proto, "--dport", r.port.toString())
        }
        cmd += listOf("-j", target)
        return cmd
    }

    private fun exec(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val exit = p.waitFor()
        if (exit != 0) throw IllegalStateException("${cmd.joinToString(" ")} 失败(exit=$exit): ${out.take(200)}")
    }

    companion object {
        const val CHAIN = "MINIME_FW"

        /**
         * 防火墙工具禁止操作的高危端口。
         * 涵盖用户指定的 80 / 8888 / 22 / 3304，并扩展到常见敏感服务端口：
         * 远程管理（SSH/Telnet/RDP）、数据库（MySQL/PostgreSQL/MSSQL/Oracle/Redis/Mongo/ES）、
         * 管理面板与明文协议（HTTP/FTP/SMB/邮件）。
         *
         * 刻意不纳入 443（HTTPS 主力端口）与 53（DNS）：这两者是防火墙收紧后保持可用性的
         * 基础通道，53 已由系统规则始终放行；若纳入会导致「收紧即断网」的反面效果。
         */
        val PROTECTED_PORTS: Set<Int> = setOf(
            20, 21,          // FTP 数据/控制通道（明文）
            22, 23,          // SSH / Telnet 远程管理
            25, 110, 143,    // SMTP / POP3 / IMAP 邮件服务
            80, 8080,        // HTTP / 常见管理面板端口
            445,             // SMB 文件共享
            1433, 1521,      // MSSQL / Oracle
            3304, 3306,      // MySQL（含用户指定的 3304）
            3389,            // RDP 远程桌面
            5432,            // PostgreSQL
            6379,            // Redis
            11211,           // Memcached
            27017,           // MongoDB
            8888,            // 常见管理面板端口
            9200             // Elasticsearch
        )
    }
}
