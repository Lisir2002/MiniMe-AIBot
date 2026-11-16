package com.minime.agent.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------- HTTP 请求/响应 DTO ----------

@Serializable
data class CreateSessionRequest(
    val title: String = "新会话"
)

@Serializable
data class CreateSessionResponse(
    val sessionId: String
)

@Serializable
data class SubmitMessageRequest(
    val content: String,
    /** 该会话使用的模型配置（第一版：type 固定 anthropic/openai/gemini，取其一）。 */
    val provider: ProviderConfigDto
)

@Serializable
data class ProviderConfigDto(
    val type: String = "anthropic",
    val apiKey: String,
    /** 完整 base URL，例如 https://maas-api.cn-huabei-1.xf-yun.com/anthropic/v1 */
    val baseUrl: String,
    /** 是否 baseUrl 已是完整端点（不再拼 v1/messages）。讯飞 Anthropic 兼容端点用 true。 */
    val useFullUrl: Boolean = false,
    val model: String = "claude-3-5-sonnet-20241022"
)

@Serializable
data class SubmitMessageResponse(
    val taskId: String,
    val accepted: Boolean = true
)

@Serializable
data class EventsResponse(
    val events: List<StoredEvent>,
    /** 下次拉取的游标；无新事件时等于请求的 after。 */
    val nextSeq: Long,
    val taskStatus: String = "running",
    /**
     * 该会话当前仍挂起、尚未决策的审批项。App 重开/回前台时据此恢复审批卡片——
     * pending_decision 事件本身是历史回放，不能判断"是否已处理"，必须单独持久化状态。
     */
    val pendingDecisions: List<PendingDecisionDto> = emptyList()
)

/** 仍等待用户审批的工具调用（服务端持久化状态，供 App 断线重连恢复）。 */
@Serializable
data class PendingDecisionDto(
    val decisionId: String,
    val taskId: String = "",
    val toolName: String = "",
    val argsPreview: String = "",
    val sessionId: String = ""
)

@Serializable
data class DecisionRequest(
    val approve: Boolean,
    /** approve 时可选：记住该类操作的授权（第一版忽略，仅记录）。 */
    val remember: Boolean = false
)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class ErrorResponse(val error: String)

/** 服务端版本信息：供 App 做「当前/期望版本」对比与协议兼容性判断。 */
@Serializable
data class VersionResponse(
    val service: String = "minime-agent-server",
    val version: String,
    val protocol: Int
)

// ---------- 事件（落库并回传给 App）----------

/**
 * 云端事件流。每条带全局递增 [seq]（每会话内递增即可，第一版用库级别自增主键）。
 * kind 取值见 [EventKind]。App 按 after=seq 增量拉取，回放成聊天流。
 */
@Serializable
data class StoredEvent(
    val seq: Long,
    val sessionId: String,
    val kind: String,
    val taskId: String = "",
    /** 事件文本正文（助手回复 / 工具结果 / 错误信息）。 */
    val text: String = "",
    /** 工具调用相关字段。 */
    val toolName: String = "",
    val toolArgs: String = "",
    val toolCallId: String = "",
    val isError: Boolean = false,
    /** thinking 过程（落库供历史展示）。 */
    val reasoning: String = "",
    val thinkingBlocksJson: String = "",
    val signature: String = "",
    val timestamp: Long = 0L
)

object EventKind {
    const val USER_MESSAGE = "user_message"
    const val ASSISTANT_TEXT = "assistant_text"
    const val TOOL_CALL_STARTED = "tool_call_started"
    const val TOOL_CALL_FINISHED = "tool_call_finished"
    /** 高风险工具待审批：任务挂起，等 App 回传决策。 */
    const val PENDING_DECISION = "pending_decision"
    const val TASK_COMPLETED = "task_completed"
    const val TASK_FAILED = "task_failed"
}

// ---------- 任务状态 ----------

object TaskStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val WAITING_APPROVAL = "waiting_approval"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
}
