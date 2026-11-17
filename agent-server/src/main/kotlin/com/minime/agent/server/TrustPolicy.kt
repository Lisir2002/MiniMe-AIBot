package com.minime.agent.server

import com.minime.code.feature.agent.domain.model.TrustLevel
import com.minime.code.feature.agent.domain.tool.ToolPermissionPolicy

/**
 * 云端工具审批的信任档位裁决（与 App 端语义对齐，抽成纯函数便于测试）。
 *
 * 服务端的 BashTool 没有 App 端那套内置灾难 rm 防护，完全信任档会真正静默执行所有工具——
 * 这是用户在切换档位时已明确确认的语义。
 */
object TrustPolicy {
    fun needsApproval(level: TrustLevel, policy: ToolPermissionPolicy): Boolean =
        when (level) {
            TrustLevel.FULL -> false          // 完全信任：全部静默放行
            TrustLevel.STRICT -> true         // 严格审批：所有工具都审批
            TrustLevel.BASIC -> policy == ToolPermissionPolicy.ASK // 基本信任：沿用工具自身策略
        }
}
