package com.minime.code.feature.agent.presentation.component

import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minime.code.R
import com.minime.code.core.theme.Radius
import com.minime.code.core.theme.Spacing
import com.minime.code.core.ui.MiniMeIcons
import com.minime.code.core.ui.decodeSampledBitmap
import com.minime.code.core.util.FileLogger
import com.minime.code.feature.agent.data.remote.cloudagent.LoginFrameResp
import com.minime.code.feature.agent.data.remote.cloudagent.LoginStateResp
import com.minime.code.feature.agent.data.remote.cloudagent.LoginStreamMsg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CloudLoginPanel"
private const val FRAME_POLL_MS = 350L
private const val STATUS_POLL_MS = 1000L
private const val FRAME_MAX_EDGE = 1600

/**
 * 云端登录面板：显示 agent-server 推送的云端 Chromium 实时画面，把用户触摸/键盘转成输入事件回传。
 *
 * 关键约束（用户明确要求）：全程只传「截图像素 + 输入坐标/文本」，App 不读取、不缓存、不搬运 Cookie/token。
 * 登录真实发生在云端浏览器里，登录态天然沉淀在云端 profile —— 这就是"手机登录、云端同步"的实质。
 *
 * [onFrame]/[onStatus] 为 suspend 回调，由 ViewModel 经 CloudAgentWorkflow 调 agent-server 的
 * /login/{id}/frame 与 /login/{id}/status；[onInput] 转发触摸/键盘；[onComplete]/[onCancel] 收尾。
 *
 * [openStream] 可选：WebSocket 推流优先（服务端主动推变化帧+状态，比轮询更跟手）。
 * 返回 null 或 Flow 结束（断线/旧服务端不支持）时自动降级到 [onFrame]/[onStatus] 轮询。
 */
@Composable
internal fun CloudLoginPanel(
    url: String,
    reason: String,
    onFrame: suspend (after: Long) -> LoginFrameResp?,
    onStatus: suspend () -> LoginStateResp?,
    onInput: (type: String, x: Int, y: Int, text: String, key: String, dx: Int, dy: Int) -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    forceCollapse: Boolean = false,
    isScrolling: Boolean = false,
    openStream: (suspend () -> Flow<LoginStreamMsg>?)? = null
) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var frameMeta by remember { mutableStateOf<LoginFrameResp?>(null) }
    var lastSeq by remember { mutableLongStateOf(0L) }
    var statusLine by remember { mutableStateOf("") }
    var inputText by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(true) }

    // 回调用 rememberUpdatedState 保持最新，避免 LaunchedEffect(Unit) 捕获过期 lambda
    val frameCb = rememberUpdatedState(onFrame)
    val statusCb = rememberUpdatedState(onStatus)
    val completeCb = rememberUpdatedState(onComplete)
    val streamCb = rememberUpdatedState(openStream)

    // 收尾去重：WS 与降级轮询都可能观察到 success，只允许触发一次完成回传
    var completionFired = false

    // 帧处理（WS/轮询共用）：只在 seq 前进且画面变化时解码更新，静态页不重绘
    suspend fun applyFrame(f: LoginFrameResp) {
        if (f.seq <= lastSeq) return
        lastSeq = f.seq
        frameMeta = f
        if (f.changed && f.jpegBase64.isNotEmpty()) {
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    decodeSampledBitmap(Base64.decode(f.jpegBase64, Base64.DEFAULT), FRAME_MAX_EDGE)
                }.getOrElse { e -> FileLogger.w(TAG, "登录帧解码失败", e); null }
            }
            if (decoded != null) bitmap = decoded
        }
    }

    // 状态处理（WS/轮询共用）：多特征文案优先展示；仅 success 终态自动收尾
    fun applyState(state: String, message: String, detail: String) {
        statusLine = message.ifBlank { detail.ifBlank { state } }
        when (state.lowercase()) {
            "success" -> if (!completionFired) { completionFired = true; completeCb.value() }
            "authenticating" -> { /* 二次验证中：保留面板并提示，等待用户在手机上完成 */ }
            "failed", "timeout", "error" -> { /* 保留面板，提示用户重试或取消 */ }
        }
    }

    // HTTP 降级轮询（旧服务端或 WS 断线时启用）
    suspend fun CoroutineScope.pollFallback() {
        launch {
            while (isActive && !completionFired) {
                val f = runCatching { frameCb.value(lastSeq) }.getOrNull()
                if (f != null && f.seq > lastSeq) applyFrame(f)
                delay(FRAME_POLL_MS)
            }
        }
        launch {
            while (isActive && !completionFired) {
                val s = runCatching { statusCb.value() }.getOrNull()
                if (s != null) applyState(s.state, s.message, s.detail)
                delay(STATUS_POLL_MS)
            }
        }
    }

    LaunchedEffect(Unit) {
        val streamFactory = streamCb.value
        val stream = if (streamFactory != null) runCatching { streamFactory() }.getOrNull() else null
        if (stream != null) {
            // 首选：WebSocket 推流。Flow 结束（服务端关闭/网络断开/旧版不支持）→ 自动降级轮询
            stream.catch { e -> FileLogger.w(TAG, "登录帧 WS 异常，降级轮询", e) }
                .collect { msg ->
                    when (msg) {
                        is LoginStreamMsg.Frame -> applyFrame(
                            LoginFrameResp(msg.seq, msg.jpegBase64, msg.width, msg.height, msg.changed)
                        )
                        is LoginStreamMsg.Status -> applyState(msg.state, msg.message, msg.detail)
                    }
                }
            if (!completionFired && isActive) pollFallback()
        } else {
            // 降级：服务端无 WS 端点 / 建连失败，走原有 HTTP 轮询
            pollFallback()
        }
    }

    val effectiveExpanded = expanded && !forceCollapse
    val panelAlpha by animateFloatAsState(
        targetValue = if (isScrolling) 0.4f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "cloud-login-panel-alpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .graphicsLayer { alpha = panelAlpha },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            // 标题栏（可折叠）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        MiniMeIcons.Globe,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.cloud_login_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = reason.ifBlank { stringResource(R.string.cloud_login_reason) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))

                    // 远程画面：按截图宽高比铺满宽度，点击/拖动坐标按比例换算回截图像素坐标
                    val ratio = frameMeta?.let {
                        if (it.height > 0) it.width.toFloat() / it.height else 0f
                    } ?: 0f
                    val imageModifier = if (ratio > 0f) {
                        Modifier.fillMaxWidth().aspectRatio(ratio)
                    } else {
                        Modifier.fillMaxWidth().height(220.dp)
                    }
                    Box(
                        modifier = imageModifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.sm))
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val meta = frameMeta ?: return@detectTapGestures
                                    if (meta.width <= 0 || meta.height <= 0 || size.width <= 0 || size.height <= 0) return@detectTapGestures
                                    val px = (offset.x / size.width * meta.width).toInt().coerceIn(0, meta.width - 1)
                                    val py = (offset.y / size.height * meta.height).toInt().coerceIn(0, meta.height - 1)
                                    onInput("click", px, py, "", "", 0, 0)
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val meta = frameMeta
                                    if (meta == null || meta.width <= 0 || meta.height <= 0 || size.width <= 0 || size.height <= 0) return@detectDragGestures
                                    val dx = (dragAmount.x / size.width * meta.width).toInt()
                                    val dy = (dragAmount.y / size.height * meta.height).toInt()
                                    onInput("scroll", 0, 0, "", "", dx, dy)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = bitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = stringResource(R.string.cloud_login_frame_desc),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    if (statusLine.isNotBlank()) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = statusLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(Spacing.sm))
                    // 键盘输入：先点画面里的输入框聚焦，再在此输入并"发送"（text 事件注入聚焦元素）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            placeholder = { Text(stringResource(R.string.cloud_login_input_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (inputText.isNotEmpty()) {
                                    onInput("text", 0, 0, inputText, "", 0, 0)
                                    inputText = ""
                                }
                            })
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        AgentActionButton(
                            text = stringResource(R.string.cloud_login_send),
                            onClick = {
                                if (inputText.isNotEmpty()) {
                                    onInput("text", 0, 0, inputText, "", 0, 0)
                                    inputText = ""
                                }
                            },
                            tone = AgentActionTone.Neutral
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AgentActionButton(
                            text = stringResource(R.string.cloud_login_enter),
                            onClick = { onInput("key", 0, 0, "", "Enter", 0, 0) },
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Neutral
                        )
                        AgentActionButton(
                            text = stringResource(R.string.cloud_login_cancel),
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Danger
                        )
                        AgentActionButton(
                            text = stringResource(R.string.cloud_login_done),
                            onClick = onComplete,
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Success
                        )
                    }
                }
            }
        }
    }
}
