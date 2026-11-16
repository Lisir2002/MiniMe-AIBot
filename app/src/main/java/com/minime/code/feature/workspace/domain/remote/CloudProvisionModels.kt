package com.minime.code.feature.workspace.domain.remote

/**
 * 云服务器「一键配置 AI 运行环境」的领域模型。
 *
 * 流程严格分三段（对应用户需求：统一预检 → 统一补齐前置 → 最后装 AI 容器）：
 * 1. [PreflightReport]：只读探测，不改服务器；
 * 2. [ProvisionPlan]：据预检生成的安装计划（含 Docker/降级决策），用户确认后执行；
 * 3. [ProvisionStep]：执行期逐项进度，流式回显到 UI。
 */

/** 单项预检结果。 */
data class PreflightItem(
    val key: String,
    val label: String,
    val status: Status,
    val detail: String
) {
    enum class Status { OK, MISSING, WARNING, UNKNOWN }
}

/** 预检总报告。 */
data class PreflightReport(
    val host: String,
    val osName: String = "未知",
    val arch: String = "未知",
    val cpuCores: String = "未知",
    val memoryMb: Long = 0,
    val diskFreeMb: Long = 0,
    val items: List<PreflightItem> = emptyList(),
    val detected: Detected = Detected()
) {
    /** 从探测项里解析出的关键能力，供生成安装计划。 */
    data class Detected(
        val hasDocker: Boolean = false,
        val dockerVersion: String? = null,
        val hasGit: Boolean = false,
        val hasCurl: Boolean = false,
        val hasBash: Boolean = false,
        val hasPython: Boolean = false,
        val hasNode: Boolean = false,
        val hasSudo: Boolean = false,
        val passwordlessSudo: Boolean = false,
        val isRoot: Boolean = false,
        val dockerHubReachable: Boolean = false,
        val diskFreeMb: Long = 0,
        val diskEnough: Boolean = true,
        val workspaceExists: Boolean = false
    )

    val canUseDocker: Boolean get() = detected.hasDocker || detected.hasSudo || detected.isRoot
}

/** 环境交付方式。 */
enum class ProvisionStrategy {
    /** 服务器已有或可装 Docker：拉预置镜像（推荐）。 */
    DOCKER,
    /** 无 Docker 且无 sudo：降级到 ~/.local 原生安装脚本。 */
    NATIVE_FALLBACK
}

/** 安装计划里的一个动作。 */
data class PlanAction(
    val id: String,
    val title: String,
    val description: String,
    val requiresSudo: Boolean = false
)

/** 生成好的安装计划。 */
data class ProvisionPlan(
    val strategy: ProvisionStrategy,
    val strategyReason: String,
    val actions: List<PlanAction>,
    val warnings: List<String> = emptyList()
)

/** 执行阶段。 */
enum class ProvisionPhase {
    IDLE, PREFLIGHT, INSTALL_PREREQUISITES, INSTALL_AI_CONTAINER, FINISHED, FAILED
}

/** 执行期的一个步骤状态。 */
data class ProvisionStep(
    val actionId: String,
    val title: String,
    val state: State,
    val output: String = ""
) {
    enum class State { PENDING, RUNNING, DONE, FAILED, SKIPPED }
}

/** 一键配置的完整 UI 状态。 */
data class CloudProvisionUiState(
    val phase: ProvisionPhase = ProvisionPhase.IDLE,
    val preflight: PreflightReport? = null,
    val plan: ProvisionPlan? = null,
    val steps: List<ProvisionStep> = emptyList(),
    val currentLog: String = "",
    val error: String? = null,
    /** 需要用户确认主机指纹时填充。 */
    val pendingHostKey: PendingHostKey? = null,
    /** 全部完成、可进入「保存并切换」时置 true。 */
    val completed: Boolean = false
) {
    data class PendingHostKey(
        val host: String, val port: Int, val fingerprint: String, val changed: Boolean
    )
}
