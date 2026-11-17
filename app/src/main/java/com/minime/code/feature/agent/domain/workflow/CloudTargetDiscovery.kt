package com.minime.code.feature.agent.domain.workflow

import com.minime.code.feature.workspace.domain.model.RemoteConnection
import com.minime.code.feature.workspace.domain.model.RemoteProtocol
import com.minime.code.feature.workspace.domain.repository.RemoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 可用云端执行目标发现器：列出所有"已安装 agent-server"的远程服务器连接。
 *
 * 判定方式：对每个 SFTP 连接拼 http://host:8080 并读 /version，能读到即视为可用目标
 * （不 SSH、不改服务器状态）。结果带短 TTL 缓存，空会话卡片/设置页可频繁读取而不反复探测。
 */
@Singleton
class CloudTargetDiscovery @Inject constructor(
    private val remoteRepository: RemoteRepository,
    private val serverManager: CloudAgentServerManager
) {
    /** 一个可用的云端执行目标。 */
    data class AvailableTarget(
        val connectionId: String,
        val name: String,
        val host: String,
        val baseUrl: String,
        /** 服务端 /version 读到的版本；读不到版本但 health 通时为 "unknown"。 */
        val version: String
    ) {
        /** 顶栏/卡片显示的短标签。 */
        val displayLabel: String get() = name.ifBlank { host }

        /**
         * 该服务端版本是否支持"工具审批三档信任"。信任档位随 App rc13 引入，
         * 旧版 agent-server 不认识 trustLevel 字段、Bash 固定每次审批，会让
         * "完全信任"失效。UI 据此提示用户升级云端服务。
         */
        val trustLevelSupported: Boolean get() = supportsTrustLevel(version)
    }

    @Volatile
    private var cached: List<AvailableTarget>? = null
    private var cachedAt = 0L

    /** 探测所有连接，返回已安装且在线的云端目标列表。 */
    suspend fun discover(forceRefresh: Boolean = false): List<AvailableTarget> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val hit = cached
        if (!forceRefresh && hit != null && now - cachedAt < CACHE_TTL_MS) return@withContext hit

        val connections = remoteRepository.getConnections().first()
            .filter { it.protocol == RemoteProtocol.SFTP }
        val results = connections.map { conn ->
            async(Dispatchers.IO) { probe(conn) }
        }.awaitAll().filterNotNull()

        cached = results
        cachedAt = System.currentTimeMillis()
        results
    }

    /** 是否至少有一台可用的云服务器（决定空会话卡片是否显示引导入口）。 */
    suspend fun hasAnyTarget(forceRefresh: Boolean = false): Boolean = discover(forceRefresh).isNotEmpty()

    /** 按连接 id 找目标（用于确认某会话绑定的服务器仍然可用）。 */
    suspend fun findById(connectionId: String): AvailableTarget? =
        discover().firstOrNull { it.connectionId == connectionId }

    private suspend fun probe(conn: RemoteConnection): AvailableTarget? {
        val baseUrl = "http://${conn.host}:8080"
        val info = serverManager.queryVersion(baseUrl).getOrNull() ?: return null
        return AvailableTarget(
            connectionId = conn.id,
            name = conn.name,
            host = conn.host,
            baseUrl = baseUrl.trimEnd('/'),
            version = info.version.ifBlank { "unknown" }
        )
    }

    /** 目标列表变更后由设置页调用，令缓存失效下次重探。 */
    fun invalidate() {
        cached = null
        cachedAt = 0L
    }

    private companion object {
        const val CACHE_TTL_MS = 20_000L
    }
}

/** 支持信任档位的最低服务端版本（含）：App rc13 首次随附的服务端。 */
const val MIN_TRUST_LEVEL_VERSION = "1.0.0-rc13"

/**
 * 判断服务端版本是否达到信任档位生效阈值。
 * 解析形如 1.0.0-rc13 / 1.0.0 的语义版本，与阈值按（主,次,修,rc序号）比较；
 * rc 序号缺省视为 0，正式版视为无穷大（rcN < 正式版）。
 * 无法解析（unknown / dev / 空）时保守返回 false，引导用户升级。
 */
fun supportsTrustLevel(version: String): Boolean {
    val v = parseServerVersion(version) ?: return false
    val min = parseServerVersion(MIN_TRUST_LEVEL_VERSION)!!
    return compareServerVersion(v, min) >= 0
}

private data class ServerSemVer(val major: Int, val minor: Int, val patch: Int, val rc: Int)

private val SERVER_VERSION_RE = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(?:rc|alpha|beta|dev)\.?(\d+))?""", RegexOption.IGNORE_CASE)

private fun parseServerVersion(s: String): ServerSemVer? {
    val m = SERVER_VERSION_RE.matchEntire(s.trim()) ?: return null
    // 没有预发布段（rc/alpha/...）视为正式版，rc 字段记为 -1（比较时作为无穷大）。
    val rcPart = m.groupValues[4]
    return ServerSemVer(
        m.groupValues[1].toIntOrNull() ?: return null,
        m.groupValues[2].toIntOrNull() ?: return null,
        m.groupValues[3].toIntOrNull() ?: return null,
        if (rcPart.isBlank()) -1 else (rcPart.toIntOrNull() ?: 0)
    )
}

/** 比较 rc 段：-1 表示正式版（视为无穷大，大于任何 rcN）。 */
private fun compareRc(a: Int, b: Int): Int = when {
    a == b -> 0
    a == -1 -> 1   // 正式版 > 任意 rc
    b == -1 -> -1  // 任意 rc < 正式版
    else -> a.compareTo(b)
}

/** 比较：主/次/修按数值；rc 段越小越旧，正式版（rc=-1）最大。 */
private fun compareServerVersion(a: ServerSemVer, b: ServerSemVer): Int {
    if (a.major != b.major) return a.major.compareTo(b.major)
    if (a.minor != b.minor) return a.minor.compareTo(b.minor)
    if (a.patch != b.patch) return a.patch.compareTo(b.patch)
    return compareRc(a.rc, b.rc)
}
