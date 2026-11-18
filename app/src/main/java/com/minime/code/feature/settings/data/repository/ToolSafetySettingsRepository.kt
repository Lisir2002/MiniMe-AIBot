package com.minime.code.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.minime.code.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.toolSafetyDataStore by preferencesDataStore(
    name = "tool_safety_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「禁用安全拦截」开关，按执行目标隔离（本机 local / 各 connectionId）。
 *
 * 开启后，AUTO 模式下连灾难性 rm（删除根目录、系统关键目录、工作区根目录等）
 * 也不再拦截，命令一律放行，Shizuku 工具也不再逐次弹窗。仅作用于 AUTO 模式。
 * 每个执行目标各存一份，切换目标时 [switchScope] 改变当前作用域。
 */
@Singleton
class ToolSafetySettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        const val LOCAL_SCOPE = "local"
        private fun scopeKey(scope: String) = booleanPreferencesKey("disable_safety_interception_$scope")
        private val LEGACY_KEY = booleanPreferencesKey("disable_safety_interception")
    }

    private val _scope = MutableStateFlow(LOCAL_SCOPE)
    val scope: Flow<String> = _scope

    fun switchScope(scope: String) {
        _scope.value = scope.ifBlank { LOCAL_SCOPE }
    }

    /** 当前作用域的开关流。 */
    val disableSafetyInterceptionFlow: Flow<Boolean> = _scope.flatMapLatest { scope ->
        context.toolSafetyDataStore.data.map { prefs ->
            prefs[scopeKey(scope)] ?: if (scope == LOCAL_SCOPE) prefs[LEGACY_KEY] ?: false else false
        }
    }

    suspend fun setDisableSafetyInterception(disabled: Boolean) {
        val scope = _scope.value
        context.toolSafetyDataStore.edit { prefs ->
            prefs[scopeKey(scope)] = disabled
            if (scope == LOCAL_SCOPE) prefs[LEGACY_KEY] = disabled
        }
    }

    suspend fun isSafetyInterceptionDisabled(): Boolean = disableSafetyInterceptionFlow.first()
}
