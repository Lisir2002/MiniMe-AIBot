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
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("cloud_agent_enabled")
        val SERVER_URL_KEY = stringPreferencesKey("cloud_agent_server_url")
        val TOKEN_KEY = stringPreferencesKey("cloud_agent_token")
        /** 新会话默认绑定的云服务器连接 id（空=本机）。 */
        val DEFAULT_CONNECTION_KEY = stringPreferencesKey("cloud_agent_default_connection")

        fun serverUrlKey(connectionId: String) = stringPreferencesKey("cloud_server_url_$connectionId")
        fun tokenKey(connectionId: String) = stringPreferencesKey("cloud_token_$connectionId")
    }

    /** 是否启用完全云端 Agent 模式。默认 false（本地执行）。 */
    val enabledFlow: Flow<Boolean> = context.cloudAgentDataStore.data.map { it[ENABLED_KEY] ?: false }

    /** 云端服务地址，如 http://154.89.153.169:8080。 */
    val serverUrlFlow: Flow<String> = context.cloudAgentDataStore.data.map { it[SERVER_URL_KEY] ?: "" }

    /** Bearer token（可选）。 */
    val tokenFlow: Flow<String> = context.cloudAgentDataStore.data.map { it[TOKEN_KEY] ?: "" }

    suspend fun setEnabled(enabled: Boolean) = context.cloudAgentDataStore.edit { it[ENABLED_KEY] = enabled }
    suspend fun setServerUrl(url: String) = context.cloudAgentDataStore.edit { it[SERVER_URL_KEY] = url.trim().trimEnd('/') }
    suspend fun setToken(token: String) = context.cloudAgentDataStore.edit { it[TOKEN_KEY] = token }

    suspend fun isEnabled(): Boolean = enabledFlow.first()
    suspend fun serverUrl(): String = serverUrlFlow.first()
    suspend fun token(): String = tokenFlow.first()

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
