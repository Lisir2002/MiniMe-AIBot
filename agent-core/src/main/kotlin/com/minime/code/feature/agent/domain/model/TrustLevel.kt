package com.minime.code.feature.agent.domain.model

/**
 * 工具审批的信任档位（用户可调，App 与云端共用）。
 *
 * 这是独立于 [AgentMode] 的横向权限策略：AgentMode 决定「AI 的工作方式」（BUILD/PLAN/AUTO），
 * TrustLevel 决定「工具调用要不要弹窗、多久弹一次」。三档由宽到严：
 *
 * - [FULL] 完全信任：所有工具静默放行（含灾难性操作，用户切换时已做安全提示确认）。
 * - [BASIC] 基本信任（默认）：无安全隐患的操作（只读工具、内置安全白名单命令）自动放行；
 *   写入 / 命令 / MCP 等有副作用的操作弹窗审批；「始终允许」会按工具+命令前缀记忆，
 *   记忆规则作用于整个工作区（PROJECT scope），跨子目录免重复审批。
 * - [STRICT] 严格审批：所有工具调用（含只读）都弹窗审批；同样支持「始终允许」记忆（工作区级）。
 *
 * 注意：PLAN 模式「规划态不执行危险工具」是产品硬约束，优先级高于任何信任档位——
 * 即便 FULL 档，PLAN 模式下的写/执行类工具仍会被拒绝。
 */
enum class TrustLevel(val wireName: String) {
    FULL("full"),
    BASIC("basic"),
    STRICT("strict");

    companion object {
        /** 默认档位：基本信任，与历史行为一致（只读自动放行，写/命令审批）。 */
        const val DEFAULT = "basic"

        fun fromWire(value: String?): TrustLevel = when (value?.lowercase()?.trim()) {
            "full" -> FULL
            "strict" -> STRICT
            else -> BASIC
        }
    }
}
