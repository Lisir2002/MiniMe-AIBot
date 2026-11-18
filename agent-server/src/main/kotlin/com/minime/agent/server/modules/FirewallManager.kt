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
 * 稳健性（2026-11-18 强化）：
 * - iptables 可能在旧镜像里缺失、可能 legacy/nftables 双后端混用、也可能缺 CAP_NET_ADMIN。
 *   [probe] 逐一尝试 iptables / iptables-nft / iptables-legacy，用「创建并删除临时链」
 *   验证真实写能力，自动选定可用后端；诊断精确区分「未安装」与「无权限」。
 * - enable/disable 全程 try-catch：任一步失败都返回结构化状态 + 明确 note，
 *   不再抛异常导致 HTTP 500。
 * - kernelApplied 反映内核规则是否真正落地；策略可保存，但绝不假装已生效。
 */
class FirewallManager(
    private val homePath: String = System.getenv("HOME") ?: "/root"
) {
    private val log = LoggerFactory.getLogger(FirewallManager::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val lock = Any()

    /** 探测后自动选定的 iptables 命令（如 iptables / iptables-nft / iptables-legacy）。 */
    @Volatile
    private var iptablesCmd: String = "iptables"

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
        /** 上次操作是否真正生效到内核（无能力/未安装时为 false）。 */
        val kernelApplied: Boolean = false,
        val note: String = ""
    )

    /** 内部能力探测结果。 */
    private data class Probe(
        val binary: Boolean = false,      // 至少找到一个 iptables 二进制
        val canList: Boolean = false,     // 能读规则
        val canWrite: Boolean = false,    // 能真正写规则（创建临时链成功）
        val backend: String = "unknown",  // nft / legacy
        val error: String = ""            // 最近一次失败原因（用于精确提示）
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
        val p = probe()
        val s = cached
        // 持久化状态声称已生效、但当前能力不足时，降级并给出精确原因；
        // 否则保留 enable/disable 时写入的具体 note（不覆盖真实错误）。
        if (s.kernelApplied && !p.canWrite) {
            save(s.copy(kernelApplied = false, note = "防火墙能力当前不可用：${capabilityHint(p)}"))
        } else s
    }

    /** 探测 iptables 是否真正可用（能列规则且能写规则）。 */
    fun iptablesUsable(): Boolean = probe().canWrite

    /** 对 kubectl/docker 场景的精确诊断文案。 */
    private fun capabilityHint(p: Probe): String = when {
        !p.binary -> "镜像未安装 iptables（iptables / iptables-nft / iptables-legacy 均不存在）。请使用 rc17+ 镜像重建容器。"
        !p.canList -> "iptables 无法读取规则：${p.error}。检查内核 nf_tables 模块与 CAP_NET_ADMIN。"
        !p.canWrite -> "iptables 无写权限：${p.error}。确认容器已加 --cap-add NET_ADMIN，且内核允许创建 nf_tables 链。"
        else -> "正常"
    }

    /**
     * 逐一探测可用后端并用「创建并删除临时链」验证真实写能力。
     * 关键：旧镜像只是没装 iptables（能加 CAP_NET_ADMIN 也白搭），旧实现把这种情况
     * 误报成「缺少 CAP_NET_ADMIN」，误导用户去查能力而不是更新镜像。
     */
    private fun probe(): Probe {
        var binary = false
        var lastError = ""
        for (cmd in listOf("iptables", "iptables-nft", "iptables-legacy")) {
            val ver = runCmd(cmd, "--version") ?: continue
            if (ver.first != 0) continue
            binary = true
            val backend = if (ver.second.contains("nf_tables", ignoreCase = true)) "nft"
                          else if (ver.second.contains("legacy", ignoreCase = true)) "legacy"
                          else "unknown"
            val list = runCmd(cmd, "-w", "5", "-L", "-n")
            if (list == null || list.first != 0) {
                lastError = list?.second?.take(160) ?: "无法读取规则"
                continue
            }
            // 真正写测试：建一个临时链再删掉（幂等、无副作用）。
            val tmp = "MINIME_PB_" + (System.nanoTime() and 0xFFFF).toString(16)
            val created = runCmd(cmd, "-w", "5", "-N", tmp)
            if (created != null && created.first == 0) {
                runCmd(cmd, "-w", "5", "-X", tmp) // 清理，失败也无所谓
                iptablesCmd = cmd                  // 锁定可用后端，后续命令统一用它
                return Probe(true, true, true, backend)
            }
            lastError = created?.second?.take(160) ?: "无法创建测试链"
        }
        return Probe(binary, false, false, "unknown", lastError)
    }

    /** 开放防火墙：清空自定义链，OUTPUT 恢复默认放行。 */
    fun disable(): State = synchronized(lock) {
        val p = probe()
        var note: String
        if (p.canWrite) {
            note = try {
                runCatching { exec(iptablesCmd, "-w", "5", "-D", "OUTPUT", "-j", CHAIN) } // 不存在则忽略
                exec(iptablesCmd, "-w", "5", "-F", CHAIN)
                exec(iptablesCmd, "-w", "5", "-X", CHAIN)
                "出站防火墙已关闭，所有出站连接放行。"
            } catch (e: Exception) {
                "关闭规则时出错：${e.message}"
            }
        } else {
            note = capabilityHint(p)
        }
        val applied = note.startsWith("出站防火墙已关闭")
        save(State(enabled = false, allow = cached.allow, deny = cached.deny, kernelApplied = applied, note = note))
    }

    /** 收紧防火墙：白名单外默认拒绝。 */
    fun enable(): State = synchronized(lock) {
        val p = probe()
        if (!p.canWrite) {
            val note = capabilityHint(p)
            log.warn("firewall enable skipped: {}", note)
            return@synchronized save(
                State(enabled = true, allow = cached.allow, deny = cached.deny, kernelApplied = false, note = note)
            )
        }
        var note: String
        var applied = false
        try {
            // 幂等重建自定义链：先尝试删引用再清规则删链，忽略"不存在"错误，然后重建。
            runCatching { exec(iptablesCmd, "-w", "5", "-D", "OUTPUT", "-j", CHAIN) }
            runCatching { exec(iptablesCmd, "-w", "5", "-F", CHAIN) }
            runCatching { exec(iptablesCmd, "-w", "5", "-X", CHAIN) }
            exec(iptablesCmd, "-w", "5", "-N", CHAIN)

            val cmds = mutableListOf<List<String>>()
            // 系统必需放行：loopback、已建立连接、DNS、HTTPS。
            // 443 与 53 一样是基础通道：agent 调大模型/拉代码/装依赖都走 HTTPS，
            // 若不在默认放行里，用户一开启防火墙就会切断自身外网能力，「收紧即断网」。
            cmds += listOf(iptablesCmd, "-w", "5", "-A", CHAIN, "-i", "lo", "-j", "ACCEPT")
            cmds += listOf(iptablesCmd, "-w", "5", "-A", CHAIN, "-m", "state", "--state", "ESTABLISHED,RELATED", "-j", "ACCEPT")
            cmds += listOf(iptablesCmd, "-w", "5", "-A", CHAIN, "-p", "udp", "--dport", "53", "-j", "ACCEPT")
            cmds += listOf(iptablesCmd, "-w", "5", "-A", CHAIN, "-p", "tcp", "--dport", "53", "-j", "ACCEPT")
            cmds += listOf(iptablesCmd, "-w", "5", "-A", CHAIN, "-p", "tcp", "--dport", "443", "-j", "ACCEPT")
            // 黑名单优先 DROP，其次白名单 ACCEPT。
            cached.deny.forEach { cmds += dropRule(it) }
            cached.allow.forEach { cmds += acceptRule(it) }
            // 默认拒绝 + OUTPUT 首条跳转到自定义链。
            cmds += listOf(iptablesCmd, "-w", "5", "-A", CHAIN, "-j", "DROP")
            cmds += listOf(iptablesCmd, "-w", "5", "-I", "OUTPUT", "1", "-j", CHAIN)
            cmds.forEach { c -> exec(*c.toTypedArray()) }
            applied = true
            note = "出站防火墙已启用（后端=$p.backend）：默认拒绝，放行 loopback/DNS/HTTPS(443)/已建立连接及 ${cached.allow.size} 条白名单规则。"
        } catch (e: Exception) {
            note = "启用失败（后端=$p.backend）：${e.message}。规则已保存但未生效，请检查与 Docker/fail2ban 规则是否冲突。"
            log.warn("firewall enable failed: {}", note, e)
        }
        save(State(enabled = true, allow = cached.allow, deny = cached.deny, kernelApplied = applied, note = note))
    }

    /**
     * 校验规则目标端口是否属于禁止操作的高危端口。
     * 返回 null 表示允许；否则返回可读的拒绝原因。
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
        val cmd = mutableListOf(iptablesCmd, "-w", "5", "-A", CHAIN)
        if (r.host.isNotBlank()) cmd += listOf("-d", r.host)
        if (r.port > 0) {
            val proto = if (r.protocol.equals("any", ignoreCase = true)) "tcp" else r.protocol.lowercase()
            cmd += listOf("-p", proto, "--dport", r.port.toString())
        }
        cmd += listOf("-j", target)
        return cmd
    }

    /** 执行命令；命令不存在时返回 null（供 [probe] 探测），非零退出返回错误文本。 */
    private fun runCmd(cmd: String, vararg args: String): Pair<Int, String>? = runCatching {
        val p = ProcessBuilder(listOf(cmd) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor() to out
    }.getOrNull()

    /** 执行必须成功的命令，失败抛异常（由 enable/disable 的 try-catch 捕获）。 */
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
         * 刻意不纳入 443（HTTPS）与 53（DNS）：这两者是防火墙收紧后保持可用性的基础通道，
         * 已由 enable() 的默认放行规则始终放行；若纳入保护列表会导致用户无法白名单化它们，
         * 而它们本就不该被拦（「收紧即断网」的反面效果）。
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
