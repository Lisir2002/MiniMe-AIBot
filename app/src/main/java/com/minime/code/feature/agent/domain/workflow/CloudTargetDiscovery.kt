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
