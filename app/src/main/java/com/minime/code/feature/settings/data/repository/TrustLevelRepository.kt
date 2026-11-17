package com.minime.code.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.minime.code.core.datastore.preferencesCorruptionHandler
import com.minime.code.feature.agent.domain.model.TrustLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.trustLevelDataStore by preferencesDataStore(
    name = "trust_level_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化工具审批的信任档位（全局，存 App 私有 DataStore，不落在工作区文件，避免被 AI 自行提权）。
 *
 * 默认 [TrustLevel.BASIC]（基本信任），与历史行为一致。用户可在「设置 → 权限与后台」、
 * 空会话「运行环境卡」随时切换；切换后权限引擎下一次评估即生效。
 */
@Singleton
class TrustLevelRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val TRUST_LEVEL_KEY = stringPreferencesKey("trust_level")
    }

    val trustLevelFlow: Flow<TrustLevel> =
        context.trustLevelDataStore.data.map { prefs ->
            TrustLevel.fromWire(prefs[TRUST_LEVEL_KEY] ?: TrustLevel.DEFAULT)
        }

    suspend fun setTrustLevel(level: TrustLevel) {
        context.trustLevelDataStore.edit { it[TRUST_LEVEL_KEY] = level.wireName }
    }

    /** 读取一次当前档位（权限评估时用）。 */
    suspend fun current(): TrustLevel = trustLevelFlow.first()
}
