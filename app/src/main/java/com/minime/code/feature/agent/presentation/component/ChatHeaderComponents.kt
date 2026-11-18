package com.minime.code.feature.agent.presentation.component

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.minime.code.R
import com.minime.code.core.theme.Radius
import com.minime.code.core.theme.Spacing
import com.minime.code.core.ui.SegmentedTabs
import com.minime.code.feature.agent.domain.model.AgentMode
import com.minime.code.feature.agent.domain.model.TrustLevel
import com.minime.code.feature.agent.domain.workflow.CloudTargetDiscovery
import com.minime.code.feature.onboarding.domain.OnboardingStep
import com.minime.code.feature.onboarding.presentation.onboardingTarget
import com.minime.code.feature.settings.presentation.component.ModelLogoIcon
import com.minime.code.core.ui.MiniMeIcons
import com.minime.code.core.ui.MiniMeIcon

@Composable
internal fun ChatHeader(
    sessionTitle: String,
    modelName: String?,
    inputTokens: Int,
    outputTokens: Int,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToGit: () -> Unit,
    onNavigateToBrowser: () -> Unit = {},
    currentMode: AgentMode,
    onToggleMode: (AgentMode) -> Unit,
    connectionState: com.minime.code.feature.agent.domain.container.ConnectionState? = null,
    /** 只读运行环境标签（如「阿里云-生产」「本机运行」），有值时在顶栏第二行显示徽标。 */
    environmentLabel: String? = null,
    /** 生效后端是云服务器（true=云图标，false=本机/远程 SSH 图标）。 */
    environmentIsCloud: Boolean = true,
    /** 非云端但执行模式为远程 SSH 时显示服务器图标（优先级低于 environmentIsCloud）。 */
    environmentIsRemote: Boolean = false,
    /** 云端服务端版本过旧、信任档位不生效时，在环境徽标上给出警示。 */
    environmentTrustWarning: Boolean = false,
    showMenuButton: Boolean = true,
    terminalActive: Boolean = false,
    gitActive: Boolean = false,
    browserActive: Boolean = false,
    // 滚动淡出：与输入框使用同一套理念——内容滚动中顶栏淡化，停止后恢复，把视觉重心让给正文。
    headerAlpha: Float = 1f
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.graphicsLayer { alpha = headerAlpha }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 大屏常驻侧栏时隐掉汉堡键：侧栏已经摆在左边，再给个开关反而困惑。
                if (showMenuButton) {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier.onboardingTarget(OnboardingStep.OPEN_SIDEBAR)
                    ) {
                        MiniMeIcon(MiniMeIcons.Menu,
                            contentDescription = stringResource(R.string.chat_open_sidebar),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Spacer(modifier = Modifier.width(Spacing.sm))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sessionTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        if (!modelName.isNullOrBlank()) {
                            ModelLogoIcon(modelName = modelName, size = 14.dp)
                        }
                        Text(
                            text = modelName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_no_model_selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onNewChat) {
                    MiniMeIcon(MiniMeIcons.Plus,
                        contentDescription = stringResource(R.string.chat_new_session),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WorkbenchIconButton(
                    icon = MiniMeIcons.GitBranch,
                    contentDescription = stringResource(R.string.chat_open_git),
                    active = gitActive,
                    onClick = onNavigateToGit
                )
                WorkbenchIconButton(
                    icon = MiniMeIcons.Terminal,
                    contentDescription = stringResource(R.string.chat_open_terminal),
                    active = terminalActive,
                    onClick = onNavigateToTerminal
                )
            }
            // 顶栏第二行：左侧显示生效的运行环境徽标（只读），右侧 token 累计统计。
            // 远程 SSH 工具执行模式已移除，不再显示本地 SSH 连接状态——那会误导用户以为云端断了。
            if (environmentLabel != null || connectionState != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (environmentLabel != null) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (environmentTrustWarning) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                MiniMeIcon(
                                    imageVector = if (environmentIsCloud) MiniMeIcons.Cloud
                                        else if (environmentIsRemote) MiniMeIcons.Server
                                        else MiniMeIcons.Smartphone,
                                    contentDescription = null,
                                    tint = if (environmentTrustWarning) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = environmentLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (environmentTrustWarning) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    }
                                )
                                if (environmentTrustWarning) {
                                    Text(
                                        text = "· ${stringResource(R.string.runtime_card_trust_server_old_short)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    } else if (connectionState != null) {
                        ConnectionIndicator(state = connectionState)
                    }
                    TokenStats(
                        inputTokens = inputTokens,
                        outputTokens = outputTokens
                    )
                }
            }
        }
    }
}

/** 顶栏工作台入口按钮：大屏右栏开着对应内容时高亮，否则看不出点一下是开还是关。 */
@Composable
private fun WorkbenchIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .then(
                    if (active) {
                        Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            MiniMeIcon(icon,
                contentDescription = contentDescription,
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun ConnectionIndicator(
    state: com.minime.code.feature.agent.domain.container.ConnectionState
) {
    val (dotColor, text) = when (state) {
        com.minime.code.feature.agent.domain.container.ConnectionState.CONNECTED ->
            MaterialTheme.colorScheme.primary to stringResource(R.string.chat_ssh_connected)
        com.minime.code.feature.agent.domain.container.ConnectionState.CONNECTING ->
            MaterialTheme.colorScheme.tertiary to stringResource(R.string.chat_ssh_connecting)
        com.minime.code.feature.agent.domain.container.ConnectionState.FAILED ->
            MaterialTheme.colorScheme.error to stringResource(R.string.chat_ssh_failed)
        com.minime.code.feature.agent.domain.container.ConnectionState.DISCONNECTED ->
            MaterialTheme.colorScheme.outline to stringResource(R.string.chat_ssh_disconnected)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TokenStats(inputTokens: Int, outputTokens: Int) {
    val inStr = formatTokenCount(inputTokens.toLong())
    val outStr = formatTokenCount(outputTokens.toLong())
    Text(
        text = "↑$inStr ↓$outStr",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun RemoteConnectingPlaceholder(
    state: com.minime.code.feature.agent.domain.container.ConnectionState
) {
    val text = when (state) {
        com.minime.code.feature.agent.domain.container.ConnectionState.CONNECTING -> stringResource(R.string.chat_connecting_remote)
        com.minime.code.feature.agent.domain.container.ConnectionState.FAILED -> stringResource(R.string.chat_remote_connect_failed)
        com.minime.code.feature.agent.domain.container.ConnectionState.DISCONNECTED -> stringResource(R.string.chat_no_remote_connection)
        com.minime.code.feature.agent.domain.container.ConnectionState.CONNECTED -> ""
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (state == com.minime.code.feature.agent.domain.container.ConnectionState.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun WelcomeState(
    bottomReserve: Dp,
    modifier: Modifier = Modifier,
    availableTargets: List<CloudTargetDiscovery.AvailableTarget> = emptyList(),
    currentConnectionId: String? = null,
    trustLevel: TrustLevel = TrustLevel.BASIC,
    discovering: Boolean = false,
    onSelectTarget: ((String?) -> Unit)? = null,
    onAddServer: (() -> Unit)? = null,
    onRefreshTargets: (() -> Unit)? = null,
    onTrustLevelChange: ((TrustLevel) -> Unit)? = null
) {
    BoxWithConstraints(
        modifier = modifier.padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        val targetOffset = minOf(-(maxHeight * 0.13f), -(bottomReserve / 2))
        val animatedOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = if (Build.VERSION.SDK_INT >= 30) snap() else tween(durationMillis = 220),
            label = "welcome-offset"
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.offset(y = animatedOffset)
        ) {
            Text(
                text = stringResource(R.string.chat_placeholder),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.chat_input_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            // 运行环境卡：空会话是决定「本机/某台云服务器 + 审批强度」的最佳时机。
            // 仅当 ViewModel 提供了切换回调时才显示交互控件（保持旧调用兼容）。
            if (onSelectTarget != null && onTrustLevelChange != null) {
                Spacer(Modifier.height(Spacing.lg))
                RuntimeEnvironmentCard(
                    availableTargets = availableTargets,
                    currentConnectionId = currentConnectionId,
                    trustLevel = trustLevel,
                    discovering = discovering,
                    onSelectTarget = onSelectTarget,
                    onAddServer = onAddServer,
                    onRefreshTargets = onRefreshTargets,
                    onTrustLevelChange = onTrustLevelChange
                )
            }
        }
    }
}

/**
 * 空会话「运行与权限」卡：
 * - 运行环境：扁平单选列表（本机 + 每台已装 agent-server 的云服务器），一步到位；
 * - 工具审批：三档分段（完全/基本/严格）。切「完全信任」需二次安全确认。
 */
@Composable
private fun RuntimeEnvironmentCard(
    availableTargets: List<CloudTargetDiscovery.AvailableTarget>,
    currentConnectionId: String?,
    trustLevel: TrustLevel,
    discovering: Boolean,
    onSelectTarget: (String?) -> Unit,
    onAddServer: (() -> Unit)?,
    onRefreshTargets: (() -> Unit)?,
    onTrustLevelChange: (TrustLevel) -> Unit
) {
    var showFullTrustConfirm by remember { mutableStateOf(false) }
    val trustValues = listOf(TrustLevel.FULL, TrustLevel.BASIC, TrustLevel.STRICT)
    val trustLabels = listOf(
        stringResource(R.string.runtime_card_trust_full),
        stringResource(R.string.runtime_card_trust_basic),
        stringResource(R.string.runtime_card_trust_strict)
    )
    val localSelected = currentConnectionId == null
    // 选中的云端服务端版本是否支持三档信任：选中本机或版本达标时为 true（无需提示）。
    val selectedTarget = availableTargets.firstOrNull { it.connectionId == currentConnectionId }
    val trustSupportedHere = localSelected || selectedTarget?.trustLevelSupported ?: true

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            // 标题行 + 刷新按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.runtime_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onRefreshTargets != null) {
                    IconButton(onClick = onRefreshTargets, modifier = Modifier.size(32.dp)) {
                        if (discovering) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = MiniMeIcons.RefreshCw,
                                contentDescription = stringResource(R.string.runtime_card_refresh),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Text(
                text = stringResource(R.string.runtime_card_env_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 本机运行（单选）
            TargetRow(
                icon = MiniMeIcons.Smartphone,
                label = stringResource(R.string.runtime_card_env_local),
                selected = localSelected,
                onClick = { if (!localSelected) onSelectTarget(null) }
            )

            // 每台云服务器（单选，带版本）
            availableTargets.forEach { t ->
                TargetRow(
                    icon = MiniMeIcons.Cloud,
                    label = t.displayLabel,
                    subtitle = stringResource(R.string.runtime_card_target_version, t.version),
                    selected = currentConnectionId == t.connectionId,
                    onClick = { if (currentConnectionId != t.connectionId) onSelectTarget(t.connectionId) }
                )
            }

            // 无可用服务器 → 引导入口
            if (availableTargets.isEmpty() && !discovering && onAddServer != null) {
                TextButton(onClick = onAddServer, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.runtime_card_add_server))
                }
            } else if (onAddServer != null) {
                TextButton(onClick = onAddServer) {
                    Text(stringResource(R.string.runtime_card_manage_servers))
                }
            }

            Spacer(Modifier.height(Spacing.xs))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
            Text(
                text = stringResource(R.string.runtime_card_trust_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SegmentedTabs(
                selected = trustValues.indexOf(trustLevel).coerceAtLeast(0),
                labels = trustLabels,
                onSelect = { idx ->
                    val target = trustValues[idx]
                    if (target == TrustLevel.FULL && trustLevel != TrustLevel.FULL) {
                        showFullTrustConfirm = true
                    } else {
                        onTrustLevelChange(target)
                    }
                }
            )
            // 云端服务端过旧：信任档位（尤其完全信任）不会在其上生效，明确提示升级。
            if (!localSelected && !trustSupportedHere && selectedTarget != null) {
                Text(
                    text = stringResource(
                        R.string.runtime_card_trust_server_old,
                        selectedTarget.version
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showFullTrustConfirm) {
        AlertDialog(
            onDismissRequest = { showFullTrustConfirm = false },
            title = { Text(stringResource(R.string.perm_trust_full_confirm_title)) },
            text = { Text(stringResource(R.string.perm_trust_full_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onTrustLevelChange(TrustLevel.FULL)
                    showFullTrustConfirm = false
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showFullTrustConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 运行环境单选行：图标 + 名称（+可选副标题）+ 单选圆点。 */
@Composable
private fun TargetRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}