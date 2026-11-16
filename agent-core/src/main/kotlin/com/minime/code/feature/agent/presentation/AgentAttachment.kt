package com.minime.code.feature.agent.presentation

import kotlinx.serialization.Serializable

/**
 * 模型直出图片/文件卡片元数据，随 AssistantText / ToolCallFinished 事件落库供 UI 渲染。
 *
 * 从 app 的 AgentUiModels.kt 上移到 agent-core：AgentEvent 在协议层引用它，
 * 必须能被 JVM 服务端复用。去掉 @Immutable（Compose 性能优化注解，core 不依赖 Compose）。
 */
@Serializable
data class AgentAttachment(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isImage: Boolean
)
