package com.minime.code.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.minime.code.core.datastore.preferencesCorruptionHandler
import com.minime.code.feature.agent.domain.model.TrustLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.trustLevelDataStore by preferencesDataStore(
    name = "trust_level_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化工具审批的信任档位（存 App 私有 DataStore，不落在工作区文件，避免被 AI 自行提权）。
 *
 * 按执行目标隔离：本机用固定 scope "local"，每台云服务器用其 connectionId。
 * 切换执行目标时 [switchScope] 改变当前作用域，权限引擎/云端提交下一次读取即取到该目标自己的档位；
 * 每个目标首次使用回退到 [TrustLevel.DEFAULT]，与历史行为一致。
 *
 * 用户可在「设置 → 权限与后台」、空会话「运行环境卡」随时切换当前目标档位。
 */
@Singleton
class TrustLevelRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        const val LOCAL_SCOPE = "local"
        private fun scopeKey(scope: String) = stringPreferencesKey("trust_level_$scope")
        /** 历史全局键：升级时作为 local scope 的初值来源。 */
        private val LEGACY_KEY = stringPreferencesKey("trust_level")
    }

    /** 当前作用域（本机 / 某 connectionId）；进程内单例，切换执行目标时更新。 */
    private val _scope = MutableStateFlow(LOCAL_SCOPE)
    val scope: Flow<String> = _scope

    /** 当前作用域的信任档位流。作用域切换后自动重新读取对应档位。 */
    val trustLevelFlow: Flow<TrustLevel> = _scope.flatMapLatest { scope ->
        context.trustLevelDataStore.data.map { prefs ->
            val stored = prefs[scopeKey(scope)]
                ?: if (scope == LOCAL_SCOPE) prefs[LEGACY_KEY] else null
            TrustLevel.fromWire(stored ?: TrustLevel.DEFAULT)
        }
    }

    /** 切换当前信任档作用域（执行目标变化时调用）。 */
    fun switchScope(scope: String) {
        _scope.value = scope.ifBlank { LOCAL_SCOPE }
    }

    /** 读取指定作用域的档位（不切换当前作用域）。 */
    suspend fun currentFor(scope: String): TrustLevel = context.trustLevelDataStore.data.map { prefs ->
        val stored = prefs[scopeKey(scope)] ?: if (scope == LOCAL_SCOPE) prefs[LEGACY_KEY] else null
        TrustLevel.fromWire(stored ?: TrustLevel.DEFAULT)
    }.first()

    suspend fun setTrustLevel(level: TrustLevel) {
        val scope = _scope.value
        context.trustLevelDataStore.edit { prefs ->
            prefs[scopeKey(scope)] = level.wireName
            // 本机同时写旧键，兼容尚未升级的读取方
            if (scope == LOCAL_SCOPE) prefs[LEGACY_KEY] = level.wireName
        }
    }

    /** 读取一次当前作用域的档位（权限评估/云端提交时用）。 */
    suspend fun current(): TrustLevel = trustLevelFlow.first()
}
