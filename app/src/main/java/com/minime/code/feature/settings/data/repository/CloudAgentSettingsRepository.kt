package com.minime.code.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.minime.code.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private val Context.cloudAgentDataStore by preferencesDataStore(
    name = "cloud_agent_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 完全云端 Agent 模式的设置。
 *
 * 与 [ExecutionModeRepository]（工具执行环境：本地PRoot/远程SSH）正交：
 * 本开关决定「Agent 循环（LLM 思考+工具编排）在 App 端还是云端服务跑」。
 * 默认关闭——用户主动开启后提问才提交到 cloudAgentServerUrl，App 退后台云端照跑。
 */
@Singleton
class CloudAgentSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        val ENABLED_KEY = booleanPreferencesKey("cloud_agent_enabled")
        val SERVER_URL_KEY = stringPreferencesKey("cloud_agent_server_url")
        val TOKEN_KEY = stringPreferencesKey("cloud_agent_token")
        /** 云端服务监听端口（字符串，用户可自定义避开占用端口）；默认 8080。 */
        val PORT_KEY = stringPreferencesKey("cloud_agent_port")
        /** 新会话默认绑定的云服务器连接 id（空=本机）。 */
        val DEFAULT_CONNECTION_KEY = stringPreferencesKey("cloud_agent_default_connection")

        const val DEFAULT_PORT = 8080

        fun serverUrlKey(connectionId: String) = stringPreferencesKey("cloud_server_url_$connectionId")
        fun tokenKey(connectionId: String) = stringPreferencesKey("cloud_token_$connectionId")
        fun portKey(connectionId: String) = stringPreferencesKey("cloud_port_$connectionId")

        /**
         * 校验端口字符串：合法返回规范化数字，非法返回 null。
         * 范围 1..65535；1024 以下需 root，但允许（部署脚本会走 sudo）。
         */
        fun validatePort(raw: String): Int? {
            val n = raw.trim().toIntOrNull() ?: return null
            return n.takeIf { it in 1..65535 }
        }

        /** 从完整 URL 提取端口；解析失败返回默认端口。 */
        fun portFromUrl(url: String): Int = runCatching {
            val parsed = java.net.URI(url.trim())
            if (parsed.port > 0) parsed.port else DEFAULT_PORT
        }.getOrDefault(DEFAULT_PORT)

        /** 用 host + 端口生成规范 serverUrl。 */
        fun serverUrl(host: String, port: Int): String = "http://$host:$port"

        /** 生成强随机访问令牌：32 字节安全随机 → Base64URL（URL/header 安全，无 +/=）。 */
        fun generateToken(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
        /** 弱令牌阈值：低于此长度提示用户（自定义短串会被服务端接受，但安全性不足）。 */
        const val MIN_TOKEN_LENGTH = 16
    }

    /** 是否启用完全云端 Agent 模式。默认 false（本地执行）。 */
    val enabledFlow: Flow<Boolean> = context.cloudAgentDataStore.data.map { it[ENABLED_KEY] ?: false }

    /** 云端服务地址，如 http://154.89.153.169:8080。 */
    val serverUrlFlow: Flow<String> = context.cloudAgentDataStore.data.map { it[SERVER_URL_KEY] ?: "" }

    /** Bearer token（可选）。 */
    val tokenFlow: Flow<String> = context.cloudAgentDataStore.data.map { it[TOKEN_KEY] ?: "" }

    /** 云端服务端口（字符串形式，UI 输入框直接绑定；默认 8080）。 */
    val portFlow: Flow<String> = context.cloudAgentDataStore.data.map { it[PORT_KEY] ?: DEFAULT_PORT.toString() }

    suspend fun setEnabled(enabled: Boolean) = context.cloudAgentDataStore.edit { it[ENABLED_KEY] = enabled }
    suspend fun setServerUrl(url: String) = context.cloudAgentDataStore.edit { it[SERVER_URL_KEY] = url.trim().trimEnd('/') }
    suspend fun setToken(token: String) = context.cloudAgentDataStore.edit { it[TOKEN_KEY] = token.trim() }
    suspend fun setPort(port: Int) = context.cloudAgentDataStore.edit { it[PORT_KEY] = port.toString() }

    /**
     * 首次进入云端页时确保有一个强随机 token（服务端 MINIME_TOKEN 与客户端 Bearer 共用同一值）。
     * 仅在 token 为空时生成——用户自定义过（哪怕清空）都不覆盖；升级场景因此天然复用旧 token，
     * 不会因重新生成导致老客户端 401。
     *
     * 生成算法：SecureRandom 32 字节 → Base64URL 无填充（43 字符，≈256bit 熵）。
     */
    suspend fun ensureToken(): String {
        val existing = token()
        if (existing.isNotBlank()) return existing
        val fresh = generateToken()
        setToken(fresh)
        return fresh
    }

    /** 重新生成一个强随机 token 并持久化（用户主动点「重新生成」时调用）。 */
    suspend fun regenerateToken(): String {
        val fresh = generateToken()
        setToken(fresh)
        return fresh
    }

    suspend fun isEnabled(): Boolean = enabledFlow.first()
    suspend fun serverUrl(): String = serverUrlFlow.first()
    suspend fun token(): String = tokenFlow.first()
    suspend fun port(): Int = validatePort(portFlow.first()) ?: DEFAULT_PORT

    // ---- 按连接 id 存取（多服务器）----------------------------------------

    /** 新会话默认绑定的云服务器连接 id；空表示本机。 */
    val defaultConnectionIdFlow: Flow<String> =
        context.cloudAgentDataStore.data.map { it[DEFAULT_CONNECTION_KEY] ?: "" }

    suspend fun defaultConnectionId(): String = defaultConnectionIdFlow.first()

    suspend fun setDefaultConnectionId(connectionId: String) =
        context.cloudAgentDataStore.edit { it[DEFAULT_CONNECTION_KEY] = connectionId }

    /** 读取指定连接的服务器地址；未单独存过时回退到全局 serverUrl（兼容旧版升级）。 */
    suspend fun serverUrlFor(connectionId: String): String {
        val scoped = context.cloudAgentDataStore.data.map { it[serverUrlKey(connectionId)] ?: "" }.first()
        return scoped.ifEmpty { serverUrl() }
    }

    /** 读取指定连接的令牌；未单独存过时回退到全局 token（兼容旧版升级）。 */
    suspend fun tokenFor(connectionId: String): String {
        val scoped = context.cloudAgentDataStore.data.map { it[tokenKey(connectionId)] ?: "" }.first()
        return scoped.ifEmpty { token() }
    }

    suspend fun setServerUrlFor(connectionId: String, url: String) =
        context.cloudAgentDataStore.edit { it[serverUrlKey(connectionId)] = url.trim().trimEnd('/') }

    suspend fun setTokenFor(connectionId: String, token: String) =
        context.cloudAgentDataStore.edit { it[tokenKey(connectionId)] = token }

    /** 读取指定连接的端口；未单独存过时从该连接的 serverUrl 解析，再回退全局端口。 */
    suspend fun portFor(connectionId: String): Int {
        val data = context.cloudAgentDataStore.data.first()
        data[portKey(connectionId)]?.let { validatePort(it)?.let { p -> return p } }
        data[serverUrlKey(connectionId)]?.let { return portFromUrl(it) }
        return data[PORT_KEY]?.let { validatePort(it) } ?: DEFAULT_PORT
    }

    suspend fun setPortFor(connectionId: String, port: Int) =
        context.cloudAgentDataStore.edit { it[portKey(connectionId)] = port.toString() }

    /** appSessionId -> 云端 sessionId 的映射键（同一 App 会话复用同一云端会话，多轮上下文留在云端）。 */
    private fun cloudSessionKey(appSessionId: String, serverUrl: String) =
        "cloud_session_${serverUrl.hashCode()}_$appSessionId"

    /** 读取该 App 会话对应的云端 sessionId；换服务器地址后自动失效（哈希进 key）。 */
    suspend fun getCloudSessionId(appSessionId: String, serverUrl: String): String? =
        context.cloudAgentDataStore.data.map { it[stringPreferencesKey(cloudSessionKey(appSessionId, serverUrl))] }.first()

    suspend fun setCloudSessionId(appSessionId: String, serverUrl: String, cloudSessionId: String) {
        context.cloudAgentDataStore.edit { it[stringPreferencesKey(cloudSessionKey(appSessionId, serverUrl))] = cloudSessionId }
    }

    suspend fun clearCloudSessionId(appSessionId: String, serverUrl: String) {
        context.cloudAgentDataStore.edit { it.remove(stringPreferencesKey(cloudSessionKey(appSessionId, serverUrl))) }
        // 同步清理游标，避免该会话映射删除后残留
        context.cloudAgentDataStore.edit { it.remove(longPreferencesKey(cursorKey(appSessionId, serverUrl))) }
    }

    // ---- 持久化拉取游标 ----------------------------------------------------
    // 关键：游标必须从「上次拉到哪里」继续，而不是每次都从 0 回放。
    // 进程被杀 / 熄屏期间-cloud 端产生的事件，重开后靠这个游标一次性增量补齐。

    private fun cursorKey(appSessionId: String, serverUrl: String) =
        "cloud_cursor_${serverUrl.hashCode()}_$appSessionId"

    /** 该会话上次已拉取到的服务端事件 seq；0 表示从头拉。 */
    suspend fun getPollCursor(appSessionId: String, serverUrl: String): Long =
        context.cloudAgentDataStore.data.map { it[longPreferencesKey(cursorKey(appSessionId, serverUrl))] ?: 0L }.first()

    suspend fun setPollCursor(appSessionId: String, serverUrl: String, seq: Long) {
        context.cloudAgentDataStore.edit { it[longPreferencesKey(cursorKey(appSessionId, serverUrl))] = seq }
    }

    // ---- 当前运行任务的 taskId（供用户主动停止云端任务）--------------------
    // 按 appSessionId + serverUrl 存储：换服务器自动失效；每轮提交时覆盖为最新 taskId。
    // 进程重建后停止仍可用——从持久化映射取出 taskId 直接发取消请求。

    private fun runningTaskKey(appSessionId: String, serverUrl: String) =
        "cloud_task_${serverUrl.hashCode()}_$appSessionId"

    suspend fun getRunningTaskId(appSessionId: String, serverUrl: String): String? =
        context.cloudAgentDataStore.data.map { it[stringPreferencesKey(runningTaskKey(appSessionId, serverUrl))] }.first()

    suspend fun setRunningTaskId(appSessionId: String, serverUrl: String, taskId: String) {
        context.cloudAgentDataStore.edit { it[stringPreferencesKey(runningTaskKey(appSessionId, serverUrl))] = taskId }
    }

    suspend fun clearRunningTaskId(appSessionId: String, serverUrl: String) {
        context.cloudAgentDataStore.edit { it.remove(stringPreferencesKey(runningTaskKey(appSessionId, serverUrl))) }
    }

    /** 列出本服务器下所有建立过映射的 appSessionId，供 App 启动/回前台时批量补拉。 */
    suspend fun allCloudAppSessionIds(serverUrl: String): List<String> {
        val prefix = "cloud_session_${serverUrl.hashCode()}_"
        return context.cloudAgentDataStore.data.map { prefs ->
            prefs.asMap().keys.mapNotNull { key ->
                val name = key.name
                name.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
            }
        }.first()
    }
}
