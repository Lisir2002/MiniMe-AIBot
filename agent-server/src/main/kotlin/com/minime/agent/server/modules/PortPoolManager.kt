package com.minime.agent.server.modules

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * 容器冷门端口池：为容器内需要监听端口的服务（预览、临时 server、代理等）提供统一分配。
 *
 * 设计目标（对应用户诉求）：
 * 1. 准确打开不常用端口：只从冷门高位段分配，排除高危端口 / 知名端口 / 常见服务端口，
 *    且分配前用真实 bind 探测端口确实空闲，避免冲突。
 * 2. 记录下来：分配记录持久化到 ~/.minimecode/ports/state.json（port / 状态 / owner / 使用次数 / 时间戳），
 *    重启后仍保留历史，服务知道哪些端口曾被使用。
 * 3. 空闲优先使用：分配时优先复用「曾使用过且最久未用」的空闲端口（LRU 轮转），
 *    让已记录、已放行的端口优先回到工作状态，而不是每次都换新端口；仅当无空闲端口时才分配新端口。
 * 4. 进程联动 + 防火墙同步：端口可绑定 owner（如 terminal 的 tab id），进程退出自动归还；
 *    分配/归还同步加入/移除防火墙白名单，收紧后这些端口仍可用。
 *
 * 端口段与容量可用环境变量覆盖：
 * - MINIME_PORT_START（默认 20000）、MINIME_PORT_END（默认 60000）
 * 排除列表见 excludedPorts()。
 */
class PortPoolManager(
    private val homePath: String = System.getenv("HOME") ?: "/root",
    private val firewall: FirewallManager? = null,
    portStart: Int? = null,
    portEnd: Int? = null
) {
    private val log = LoggerFactory.getLogger(PortPoolManager::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val lock = Any()

    private val portStart: Int = (portStart ?: System.getenv("MINIME_PORT_START")?.toIntOrNull() ?: 20000)
        .coerceIn(1024, 65535)
    private val portEnd: Int = (portEnd ?: System.getenv("MINIME_PORT_END")?.toIntOrNull() ?: 60000)
        .coerceIn(portStart, 65535)

    @Serializable
    data class PortRecord(
        val port: Int,
        @Volatile var inUse: Boolean = false,
        @Volatile var owner: String = "",       // 绑定进程/任务 id，空表示无归属
        @Volatile var note: String = "",
        var acquiredAt: Long = 0L,              // 最近一次分配时间
        var releasedAt: Long = 0L,              // 最近一次归还时间（LRU 复用依据）
        var useCount: Int = 0                   // 累计使用次数
    )

    @Serializable
    data class State(
        val records: List<PortRecord> = emptyList()
    )

    private val stateFile: File get() = File(homePath, ".minimecode/ports/state.json")

    @Volatile
    private var cached: State = load()

    private fun load(): State = runCatching {
        if (stateFile.isFile) json.decodeFromString<State>(stateFile.readText()) else State()
    }.getOrDefault(State())

    private fun save(state: State) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(json.encodeToString(state))
        cached = state
    }

    /**
     * 排除的端口：高危端口（与防火墙工具同一集合）、知名端口（<1024）、
     * 常见服务/开发端口，以及本服务自身端口段之外。这样每个分配出去的都确属"不常用端口"。
     */
    fun excludedPorts(): Set<Int> = buildSet {
        addAll(FirewallManager.PROTECTED_PORTS)
        addAll(COMMON_SERVICE_PORTS)
    }

    fun isExcluded(port: Int): Boolean = port !in portStart..portEnd || port in excludedPorts()

    /** 端口当前是否真的可绑定（避免分给已被别的进程占用的端口）。 */
    private fun isPortFree(port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress("0.0.0.0", port))
            true
        }
    }.getOrDefault(false)

    /**
     * 分配一个端口。
     *
     * 策略（空闲优先）：
     * 1. 若指定 preferredPort 且合法、空闲、未占用 → 直接使用（复用记录或新建记录）。
     * 2. 否则从已记录且处于空闲的端口中，按「曾使用过优先 + 最久未用优先」排序取第一个仍空闲的 → 复用。
     * 3. 仍无可用 → 在端口段内顺序找一个合法、未被记录、且真实空闲的新端口 → 新增记录。
     * 4. 都不行 → 返回 null（端口池耗尽）。
     */
    fun acquire(
        owner: String = "",
        note: String = "",
        preferredPort: Int = 0
    ): PortRecord? = synchronized(lock) {
        val now = System.currentTimeMillis()
        val records = cached.records.toMutableList()

        if (preferredPort > 0) {
            if (isExcluded(preferredPort)) {
                log.warn("申请端口 {} 命中排除列表，拒绝", preferredPort)
                return@synchronized null
            }
            val existing = records.firstOrNull { it.port == preferredPort }
            if (existing != null && existing.inUse) return@synchronized null
            if (!isPortFree(preferredPort)) return@synchronized null
            if (existing != null) {
                existing.inUse = true; existing.owner = owner; existing.note = note
                existing.acquiredAt = now; existing.useCount++
            } else {
                records += PortRecord(preferredPort, true, owner, note, now, 0L, 1)
            }
            save(State(records))
            firewall?.addRule("allow", FirewallManager.Rule(port = preferredPort))
            log.info("分配端口(指定)={} owner={} 累计使用={}", preferredPort, owner, records.first { it.port == preferredPort }.useCount)
            return@synchronized records.first { it.port == preferredPort }
        }

        // 空闲优先复用：曾使用过(useCount>0)优先，其次最久未归还(releasedAt 小)优先。
        val reusable = records.filter { !it.inUse && !isExcluded(it.port) }
            .sortedWith(compareByDescending<PortRecord> { it.useCount > 0 }
                .thenBy { it.releasedAt }.thenBy { it.acquiredAt })
        for (r in reusable) {
            if (isPortFree(r.port)) {
                r.inUse = true; r.owner = owner; r.note = note
                r.acquiredAt = now; r.useCount++
                save(State(records))
                firewall?.addRule("allow", FirewallManager.Rule(port = r.port))
                log.info("复用空闲端口={} owner={} 累计使用={}", r.port, owner, r.useCount)
                return@synchronized r
            }
        }

        // 无空闲可复用 → 顺序取新端口。
        val recorded = records.map { it.port }.toSet()
        var picked: PortRecord? = null
        for (p in portStart..portEnd) {
            if (p in recorded || isExcluded(p)) continue
            if (!isPortFree(p)) continue
            picked = PortRecord(p, true, owner, note, now, 0L, 1)
            records += picked
            break
        }
        if (picked == null) {
            log.warn("端口池耗尽: 范围 {}-{} 排除 {} 个", portStart, portEnd, excludedPorts().size)
            return@synchronized null
        }
        save(State(records))
        firewall?.addRule("allow", FirewallManager.Rule(port = picked.port))
        log.info("分配新端口={} owner={}", picked.port, owner)
        picked
    }

    /** 按端口归还；unknown 返回 false。归还会同步移除防火墙白名单规则。 */
    fun release(port: Int): Boolean = synchronized(lock) {
        val records = cached.records.toMutableList()
        val r = records.firstOrNull { it.port == port } ?: return@synchronized false
        if (!r.inUse) return@synchronized false
        r.inUse = false; r.owner = ""; r.note = ""
        r.releasedAt = System.currentTimeMillis()
        save(State(records))
        firewall?.removeRule("allow", FirewallManager.Rule(port = port))
        log.info("归还端口={}", port)
        true
    }

    /** 按 owner 批量归还（进程退出回调用），返回实际归还的端口列表。 */
    fun releaseByOwner(owner: String): List<Int> = synchronized(lock) {
        if (owner.isBlank()) return@synchronized emptyList()
        val records = cached.records.toMutableList()
        val freed = mutableListOf<Int>()
        val now = System.currentTimeMillis()
        records.forEach {
            if (it.inUse && it.owner == owner) {
                it.inUse = false; it.owner = ""; it.note = ""
                it.releasedAt = now
                freed += it.port
                firewall?.removeRule("allow", FirewallManager.Rule(port = it.port))
            }
        }
        if (freed.isNotEmpty()) save(State(records))
        if (freed.isNotEmpty()) log.info("进程 {} 退出，自动归还端口 {}", owner, freed)
        freed
    }

    /** 把某个已分配端口改绑到新 owner（如 terminal 启动后把端口关联到 tab id）。 */
    fun bind(port: Int, owner: String): Boolean = synchronized(lock) {
        val records = cached.records.toMutableList()
        val r = records.firstOrNull { it.port == port && it.inUse } ?: return@synchronized false
        r.owner = owner
        save(State(records))
        true
    }

    fun get(port: Int): PortRecord? = synchronized(lock) { cached.records.firstOrNull { it.port == port } }
    fun list(): State = synchronized(lock) { cached }
    fun stats(): Map<String, Int> = synchronized(lock) {
        val rs = cached.records
        mapOf(
            "total" to rs.size,
            "inUse" to rs.count { it.inUse },
            "free" to rs.count { !it.inUse },
            "reusable" to rs.count { !it.inUse && it.useCount > 0 }
        )
    }

    /** 从命令行中提取冷门端口并把它们绑定到 owner（terminal start 时自动关联）。 */
    fun bindPortsInCommand(command: String, owner: String): List<Int> = synchronized(lock) {
        val nums = PORT_REGEX.findAll(command).mapNotNull { it.value.toIntOrNull() }.toSet()
        if (nums.isEmpty()) return@synchronized emptyList()
        val records = cached.records.toMutableList()
        val bound = mutableListOf<Int>()
        records.forEach { r ->
            if (r.inUse && r.port in nums && r.owner.isBlank()) {
                r.owner = owner
                bound += r.port
            }
        }
        if (bound.isNotEmpty()) save(State(records))
        bound
    }

    companion object {
        // 常见服务 / 开发端口，即便落在端口段内也跳过，保证"不常用"。
        val COMMON_SERVICE_PORTS: Set<Int> = setOf(
            1024, 1080, 1433, 1521, 2049, 3000, 3306, 3389, 4443, 5000, 5432,
            5601, 6379, 7000, 7070, 8000, 8008, 8080, 8081, 8088, 8090, 8443,
            8500, 8880, 8888, 9000, 9090, 9092, 9200, 10000, 11211, 16379,
            27017, 28017, 50000, 50070
        )
        private val PORT_REGEX = Regex("(?<!\\d)(\\d{4,5})(?!\\d)")
    }
}
