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
    val provider: ProviderConfigDto,
    /** 工具审批信任档位（full/basic/strict），缺省 basic。随任务持久化，供异步审批裁决。 */
    val trustLevel: String = "basic"
)

@Serializable
data class ProviderConfigDto(
    val type: String = "anthropic",
    val apiKey: String,
    /** 完整 base URL，例如 https://maas-api.cn-huabei-1.xf-yun.com/anthropic/v1 */
    val baseUrl: String,
    /**
     * 是否 baseUrl 已是完整端点（不再拼 /v1/messages）。默认 false 即可：
     * 例如 https://maas-api.cn-huabei-1.xf-yun.com/anthropic/v1 会自动拼成 .../anthropic/v1/messages。
     * 只有 baseUrl 已经写到 .../messages（完整端点）时才设为 true，否则会 404。
     */
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
    val pendingDecisions: List<PendingDecisionDto> = emptyList(),
    /**
     * 该会话当前仍挂起、等待用户在手机云端浏览器登录的会话。App 重开/回前台时据此恢复登录面板——
     * login_required 事件是历史回放，不能判断登录是否已完成，必须单独按持久化状态返回。
     * 与 pendingDecisions 分开：登录会话由 /login/{id}/complete 唤醒，不走 /decisions/{id}。
     */
    val pendingLogins: List<PendingLoginDto> = emptyList()
)

/** 仍等待用户登录的云端浏览器会话（供 App 断线重连恢复登录面板）。 */
@Serializable
data class PendingLoginDto(
    val loginId: String,
    val url: String = "",
    val reason: String = "",
    val taskId: String = "",
    val sessionId: String = ""
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

// ---------- 云端登录会话（手机遥控云端浏览器完成登录）----------

/** POST /login/{id}/input：把手机的触摸/键盘事件转成浏览器输入。 */
@Serializable
data class LoginInputRequest(
    val type: String,               // click / text / key / scroll
    val x: Int = 0,
    val y: Int = 0,
    val text: String = "",
    val key: String = "",
    val dx: Int = 0,
    val dy: Int = 0
)

/** GET /login/{id}/frame 响应：增量帧（静态页 changed=false 时 App 不必重绘）。 */
@Serializable
data class LoginFrameResponse(
    val seq: Long = 0,
    val jpegBase64: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val changed: Boolean = false
)

/** GET /login/{id}/status 响应。 */
@Serializable
data class LoginStateResponse(
    val loginId: String,
    // waiting / login / authenticating(2FA 中间态) / success / failed / timeout
    val state: String = "waiting",
    val url: String = "",
    val message: String = "",
    val detail: String = ""          // 与 state 一致的机器可读状态，供 App 精确识别中间态
)

/** LOGIN_REQUIRED 事件 text 字段的结构（App 据此弹登录面板）。 */
@Serializable
data class LoginRequiredPayload(
    val loginId: String,
    val url: String,
    val reason: String,
    val taskId: String = ""
)

/** 服务端版本信息：供 App 做「当前/期望版本」对比与协议兼容性判断。 */
@Serializable
data class VersionResponse(
    val service: String = "minime-agent-server",
    val version: String,
    val protocol: Int
)

// ---------- 环境数据（供 App 侧边栏/skill/授权按执行目标切换）----------

/** 云端工作区的一个文件/目录条目，字段与 App FileEntry 对齐（不含平台相关的 localFile）。 */
@Serializable
data class WorkspaceFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val permissions: String = "---"
)

/** GET /workspace/list 响应。 */
@Serializable
data class WorkspaceListResponse(
    val path: String,
    val entries: List<WorkspaceFileEntry>,
    val error: String = ""
)

/** GET /workspace/read 响应。 */
@Serializable
data class WorkspaceReadResponse(
    val path: String,
    val content: String,
    val error: String = ""
)

/** 云端技能条目（供 App 设置页按执行目标展示，instructions 不含正文以免过大）。 */
@Serializable
data class CloudSkillEntry(
    val name: String,
    val description: String = "",
    val requiredTools: List<String> = emptyList(),
    val dirPath: String = ""
)

/** GET /skills 响应。 */
@Serializable
data class SkillsResponse(val skills: List<CloudSkillEntry>)

/** 单个工具在该服务端的授权策略。 */
@Serializable
data class ToolPolicyEntry(
    val name: String,
    val permissionPolicy: String = "ASK"
)

/**
 * GET /policy 响应：描述该服务端支持的信任档与工具授权策略，
 * App 据此在切换到该执行目标时展示真实可用的工具/授权，而非沿用本机配置。
 */
@Serializable
data class PolicyResponse(
    val trustLevels: List<String> = listOf("full", "basic", "strict"),
    val tools: List<ToolPolicyEntry> = emptyList(),
    val protocol: Int = ServerVersion.PROTOCOL
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
    /** 浏览器撞到登录墙：任务挂起，等用户在手机云端浏览器登录（text 为含 loginId/url 的 JSON）。 */
    const val LOGIN_REQUIRED = "login_required"
    /** 用户在云端浏览器登录成功，任务恢复执行。 */
    const val LOGIN_SUCCEEDED = "login_succeeded"
    /** 用户取消登录或登录会话超时/结束。 */
    const val LOGIN_CANCELLED = "login_cancelled"
    const val TASK_COMPLETED = "task_completed"
    const val TASK_FAILED = "task_failed"
    /** 用户主动停止：任务被取消，App 据此收尾为「已停止」。 */
    const val TASK_CANCELLED = "task_cancelled"
}

// ---------- 任务状态 ----------

object TaskStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val WAITING_APPROVAL = "waiting_approval"
    /** 等待用户在云端浏览器完成登录。 */
    const val WAITING_LOGIN = "waiting_login"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}
