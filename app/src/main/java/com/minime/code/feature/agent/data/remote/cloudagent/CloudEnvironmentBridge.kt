package com.minime.code.feature.agent.data.remote.cloudagent

import com.minime.code.feature.agent.domain.workflow.CloudTargetResolver.CloudTarget
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前执行目标的云端环境桥接：在 AIAgentViewModel（会话/目标切换）与 SettingsViewModel（skill/授权列表）
 * 之间共享"当前是否处于云端目标及其连接信息"，避免两个 ViewModel 直接耦合。
 *
 * 仅保存连接元数据（baseUrl/token），不缓存数据本身；读取 skill/policy 时按当前目标实时走 API。
 */
@Singleton
class CloudEnvironmentBridge @Inject constructor(
    private val api: CloudAgentApi
) {
    @Volatile
    private var target: CloudTarget? = null

    /** 当前云端目标；null 表示本机执行。 */
    fun currentTarget(): CloudTarget? = target

    fun setTarget(t: CloudTarget?) {
        target = t
    }

    /** 当前目标的云端 skill 列表；本机时返回 null（由调用方回退本地 SkillRepository）。 */
    suspend fun cloudSkills(): List<CloudSkillEntry>? {
        val t = target ?: return null
        val auth = if (t.token.isBlank()) "" else "Bearer ${t.token}"
        return api.listSkills(t.baseUrl, auth).skills
    }
}
