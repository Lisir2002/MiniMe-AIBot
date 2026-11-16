package com.minime.code.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
    }
}
