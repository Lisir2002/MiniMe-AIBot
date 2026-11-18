package com.minime.code.feature.agent.domain.workflow

import com.minime.code.feature.agent.data.local.dao.ChatSessionDao
import com.minime.code.feature.settings.data.repository.CloudAgentSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 执行目标解析器：把「会话」翻译成「本机 / 某台云服务器」，并给出该服务器的地址与令牌。
 *
 * 这是多服务器支持的核心收敛点——所有需要决定"这次去哪台机器跑/拉"的地方都经过它：
 * - [DelegatingAgentWorkflow] 路由本地/云端；
 * - [CloudAgentWorkflow] 提交提问、审批回传；
 * - [CloudAgentCatchUp] 断线补拉。
 *
 * 解析规则（唯一真相源 = 会话的 cloudConnectionId）：
 * 1. 会话绑定了 cloudConnectionId → 该服务器（按 id 取 url/token）；
 * 2. cloudConnectionId == null → 本机（返回 null），**绝不**回退到全局开关。
 *
 * 重要：不再读取全局云端开关 [CloudAgentSettingsRepository.isEnabled] 参与路由。
 * 该开关是旧版单服务器时代的遗留（selectServer 时置 true 后从不关闭），
 * 曾导致「用户在运行与连接卡显式选了本机（cloudConnectionId=null），
 * 消息/思考却仍被这个僵尸开关偷偷发回云端」的严重缺陷。
 * 现在新会话创建时已用 defaultConnectionId 显式预填目标，会话级字段足以表达一切，
 * 因此 cloudConnectionId 必须是唯一判据，与 UI 徽标（currentCloudConnectionId != null）同源。
 *
 * 由于云端 sessionId 与事件游标的 key 都带 serverUrl 哈希，不同服务器天然隔离，
 * 同一 App 的多个会话可各跑各的、互不串台。
 */
@Singleton
class CloudTargetResolver @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val settings: CloudAgentSettingsRepository
) {
    /** 会话在云端运行的目标信息。 */
    data class CloudTarget(
        val connectionId: String?, // null=兼容旧版全局配置
        val baseUrl: String,
        val token: String
    )

    /** 解析会话的云端目标；返回 null 表示该会话应在本机运行。 */
    suspend fun resolve(sessionId: String?): CloudTarget? {
        if (sessionId.isNullOrBlank()) return null
        // 唯一真相源：会话绑定的目标。未绑定（含显式选本机）一律本机，
        // 不再用全局开关兜底——那是「选本机仍走云端」缺陷的根因。
        val boundId = chatSessionDao.getById(sessionId)?.cloudConnectionId ?: return null
        if (boundId.isBlank()) return null
        val url = settings.serverUrlFor(boundId)
        if (url.isBlank()) return null
        return CloudTarget(boundId, url, settings.tokenFor(boundId))
    }

    /** 该会话当前是否在云端运行（供 UI 徽标、路由判断使用）。 */
    suspend fun isCloudSession(sessionId: String?): Boolean = resolve(sessionId) != null

    /**
     * 清除会话在当前目标服务器上建立的云端 session 与事件游标映射。
     * 切换执行目标（含切回本机）前调用：换服务器后绝不能沿用旧服务器的云端上下文/游标，
     * 否则新服务器上会找不到旧 cloudSessionId，或误从旧游标续拉导致消息错乱。
     */
    suspend fun clearSessionMapping(sessionId: String) {
        // 先解析"切换前"的目标（此时会话仍绑定旧 connectionId）
        val old = resolve(sessionId) ?: return
        settings.clearCloudSessionId(sessionId, old.baseUrl)
    }
}
