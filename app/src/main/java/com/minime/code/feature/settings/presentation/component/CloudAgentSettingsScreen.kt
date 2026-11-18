package com.minime.code.feature.settings.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minime.code.R
import com.minime.code.core.theme.Radius
import com.minime.code.core.theme.Spacing
import com.minime.code.core.theme.semanticColors
import com.minime.code.core.ui.MiniMeIcon
import com.minime.code.core.ui.MiniMeIcons
import com.minime.code.feature.settings.presentation.CloudAgentSettingsViewModel

/**
 * 完全云端 Agent 内容区：模式开关 + 服务器选择 + 容器检测/部署 + 版本。
 *
 * 抽成可嵌入的 Body，供「服务器与云端」合并页作为独立 tab 复用；
 * [CloudAgentSettingsScreen] 是它带顶栏的薄壳（保留给可能的独立入口）。
 */
@Composable
internal fun CloudAgentSettingsBody(
    viewModel: CloudAgentSettingsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    val copied = remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
            // 说明卡
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.md),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.md),
                    verticalAlignment = Alignment.Top
                ) {
                    MiniMeIcon(
                        MiniMeIcons.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        stringResource(R.string.cloud_agent_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // 启用开关
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.md),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.cloud_agent_enable), fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.cloud_agent_enable_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.enabled, onCheckedChange = { viewModel.setEnabled(it) })
                }
            }

            // 服务器选择：复用「连接与同步」已保存的服务器（单一事实源）
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.md),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Text(
                        stringResource(R.string.cloud_agent_server_section),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        stringResource(R.string.cloud_agent_use_saved_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    if (state.serverChoices.isEmpty()) {
                        Text(
                            stringResource(R.string.cloud_agent_no_saved_server),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.warning
                        )
                    } else {
                        state.serverChoices.forEach { choice ->
                            val selected = choice.id == state.selectedConnectionId
                            OutlinedButton(
                                onClick = { viewModel.selectServer(choice) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                MiniMeIcon(MiniMeIcons.Server, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("${choice.name}  ·  ${choice.host}")
                            }
                        }
                    }
                }
            }

            // 容器检测：部署前探测专属 AIAgent 容器 + 版本核对（INSTALL/UPGRADE/UP_TO_DATE）
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.md),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.cloud_agent_inspect_section),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { viewModel.inspectContainer() },
                            enabled = !state.inspecting && state.selectedConnectionId != null
                        ) {
                            if (state.inspecting) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.cloud_agent_inspecting))
                            } else {
                                Text(stringResource(R.string.cloud_agent_inspect))
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.cloud_agent_inspect_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val ins = state.inspection
                    if (ins != null) {
                        Spacer(Modifier.height(Spacing.sm))
                        val (insText, insColor) = when (ins.action) {
                            com.minime.code.feature.agent.domain.workflow.CloudAgentServerManager.Inspection.Action.INSTALL ->
                                stringResource(R.string.cloud_agent_inspect_absent, ins.latestVersion ?: "?") to MaterialTheme.semanticColors.warning
                            com.minime.code.feature.agent.domain.workflow.CloudAgentServerManager.Inspection.Action.UPGRADE ->
                                stringResource(
                                    R.string.cloud_agent_inspect_upgrade,
                                    ins.currentVersion ?: "?", ins.latestVersion ?: "?"
                                ) to MaterialTheme.semanticColors.warning
                            com.minime.code.feature.agent.domain.workflow.CloudAgentServerManager.Inspection.Action.UP_TO_DATE ->
                                stringResource(R.string.cloud_agent_inspect_latest, ins.currentVersion ?: "") to MaterialTheme.semanticColors.success
                            com.minime.code.feature.agent.domain.workflow.CloudAgentServerManager.Inspection.Action.UNKNOWN ->
                                stringResource(R.string.cloud_agent_inspect_unknown) to MaterialTheme.semanticColors.warning
                        }
                        Text(insText, style = MaterialTheme.typography.bodySmall, color = insColor)
                    }
                }
            }

            // 访问令牌：部署前自定义或生成，服务端 MINIME_TOKEN 与客户端 Bearer 共用同一值
            val tokenVisible = remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.md),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MiniMeIcon(MiniMeIcons.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            stringResource(R.string.cloud_agent_token_section),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        stringResource(R.string.cloud_agent_token_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = state.token,
                        onValueChange = { viewModel.setToken(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.cloud_agent_token)) },
                        placeholder = { Text(stringResource(R.string.cloud_agent_token_hint)) },
                        visualTransformation = if (tokenVisible.value) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            androidx.compose.material3.IconButton(onClick = { tokenVisible.value = !tokenVisible.value }) {
                                MiniMeIcon(
                                    if (tokenVisible.value) MiniMeIcons.EyeOff else MiniMeIcons.Eye,
                                    contentDescription = stringResource(R.string.cloud_agent_token_toggle_visible),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                    // 弱令牌提示：短于阈值时警告（自定义短串服务端仍接受，但安全性不足）
                    if (state.token.isNotBlank() && state.token.length < com.minime.code.feature.settings.data.repository.CloudAgentSettingsRepository.MIN_TOKEN_LENGTH) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            stringResource(R.string.cloud_agent_token_weak),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.warning
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedButton(onClick = { viewModel.regenerateToken() }) {
                            MiniMeIcon(MiniMeIcons.RefreshCw, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cloud_agent_token_regenerate))
                        }
                        if (state.token.isNotBlank()) {
                            OutlinedButton(onClick = {
                                clipboardManager.setText(AnnotatedString(state.token))
                                copied.value = true
                            }) {
                                MiniMeIcon(MiniMeIcons.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(if (copied.value) R.string.cloud_agent_token_copied else R.string.cloud_agent_token_copy))
                            }
                        }
                    }
                }
            }

            // 服务器地址由上方「选择已保存服务器」自动推导（单一事实源），不再手填。

            // 测试连接
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { viewModel.testConnection() }, enabled = !state.testing) {
                    if (state.testing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cloud_agent_testing))
                    } else {
                        MiniMeIcon(MiniMeIcons.Activity, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cloud_agent_test))
                    }
                }
            }

            // 测试结果
            AnimatedVisibility(visible = state.testResult != null) {
                val result = state.testResult.orEmpty()
                when {
                    result == "ok" -> Text(
                        stringResource(R.string.cloud_agent_test_ok),
                        color = MaterialTheme.semanticColors.success,
                        style = MaterialTheme.typography.bodySmall
                    )
                    result == "need_url" -> Text(
                        stringResource(R.string.cloud_agent_need_url),
                        color = MaterialTheme.semanticColors.warning,
                        style = MaterialTheme.typography.bodySmall
                    )
                    result.startsWith("fail") -> Text(
                        stringResource(R.string.cloud_agent_test_fail, "请检查地址与网络"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // 版本对比 + 一键部署/升级（复用已保存连接的 SSH 能力）
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.md),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.cloud_agent_version_section),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.checkServerVersion() }, enabled = !state.version.checking) {
                            Text(stringResource(R.string.cloud_agent_refresh_version))
                        }
                    }
                    val v = state.version
                    when {
                        v.checking -> Text(
                            stringResource(R.string.cloud_agent_version_checking),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        v.current == null -> Text(
                            stringResource(R.string.cloud_agent_version_unknown),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.warning
                        )
                        else -> {
                            Text(
                                stringResource(R.string.cloud_agent_version_current, v.current!!),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                stringResource(R.string.cloud_agent_version_expected, v.expected),
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (v.protocol != null && v.protocol!! >= 0) {
                                Text(
                                    stringResource(R.string.cloud_agent_version_protocol, v.protocol!!),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val matched = v.current == v.expected || v.expected == "1.0.0"
                            Text(
                                stringResource(if (matched) R.string.cloud_agent_version_ok else R.string.cloud_agent_version_mismatch),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (matched) MaterialTheme.semanticColors.success else MaterialTheme.semanticColors.warning
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = { viewModel.deployOrUpgrade() },
                        enabled = !state.deploying && state.selectedConnectionId != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.deploying) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.cloud_agent_deploying))
                        } else {
                            MiniMeIcon(MiniMeIcons.UploadCloud, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            val deployLabel = when (state.inspection?.action) {
                                com.minime.code.feature.agent.domain.workflow.CloudAgentServerManager.Inspection.Action.INSTALL ->
                                    R.string.cloud_agent_deploy_install
                                com.minime.code.feature.agent.domain.workflow.CloudAgentServerManager.Inspection.Action.UPGRADE ->
                                    R.string.cloud_agent_deploy_upgrade
                                com.minime.code.feature.agent.domain.workflow.CloudAgentServerManager.Inspection.Action.UP_TO_DATE ->
                                    R.string.cloud_agent_deploy_skip
                                else -> R.string.cloud_agent_deploy
                            }
                            Text(stringResource(deployLabel))
                        }
                    }
                    state.deployResult?.let { result ->
                        Spacer(Modifier.height(Spacing.sm))
                        when {
                            result == "no_connection" -> Text(
                                stringResource(R.string.cloud_agent_deploy_need_conn),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.semanticColors.warning
                            )
                            result.startsWith("ok") -> Text(
                                stringResource(R.string.cloud_agent_deploy_ok, result.removePrefix("ok:")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.semanticColors.success
                            )
                            result.startsWith("fail") -> Text(
                                stringResource(R.string.cloud_agent_deploy_fail, result.removePrefix("fail:")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (state.deployLog.isNotBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            stringResource(R.string.cloud_agent_deploy_log),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            state.deployLog,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            Text(
                stringResource(R.string.cloud_agent_setup_guide),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
    }
}

/** 完全云端 Agent 设置页（带顶栏的薄壳）。现在主要通过「服务器与云端」合并页进入。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAgentSettingsScreen(
    viewModel: CloudAgentSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_agent_title)) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        MiniMeIcon(MiniMeIcons.ArrowLeft, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(android.R.string.cancel))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        CloudAgentSettingsBody(
            viewModel = viewModel,
            modifier = Modifier.padding(padding)
        )
    }
}
