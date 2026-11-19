package com.minime.code.feature.workspace.presentation.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minime.code.R
import com.minime.code.core.theme.Radius
import com.minime.code.core.theme.Spacing
import com.minime.code.core.theme.semanticColors
import com.minime.code.core.ui.MiniMeIcon
import com.minime.code.core.ui.MiniMeIcons
import com.minime.code.feature.workspace.domain.remote.CloudProvisionUiState
import com.minime.code.feature.workspace.domain.remote.PreflightItem
import com.minime.code.feature.workspace.domain.remote.ProvisionPhase
import com.minime.code.feature.workspace.domain.remote.ProvisionStep

/**
 * 云服务器「一键配置 AI 运行环境」向导。
 *
 * 阶段：① 填写服务器 → ② 统一预检报告 → ③ 安装计划确认 → ④ 流式安装进度 → ⑤ 完成启用。
 * 全程复用 [CloudProvisionViewModel] 驱动，界面状态来自 [CloudProvisioner.uiState]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudProvisionScreen(
    viewModel: CloudProvisionViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onEnabled: () -> Unit = {}
) {
    val state by viewModel.provisionState.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val loginKeys by viewModel.loginKeys.collectAsStateWithLifecycle()

    LaunchedEffect(enabled) { if (enabled) onEnabled() }

    Scaffold(
        containerColor = com.minime.code.feature.settings.presentation.component.settingsPageBackground(),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = com.minime.code.feature.settings.presentation.component.settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = { Text("云端 AI 环境") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        MiniMeIcon(MiniMeIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // 阶段指示
            StepIndicator(state.phase)

            // ① 表单（预检之前可编辑）
            if (state.phase == ProvisionPhase.IDLE && state.preflight == null) {
                ServerForm(
                    form = form,
                    loginKeys = loginKeys,
                    onFormChange = viewModel::updateForm,
                    onPreflight = { viewModel.runPreflight() },
                    canSubmit = form.host.isNotBlank()
                )
            }

            // 主机指纹确认
            state.pendingHostKey?.let { pk ->
                HostKeyCard(
                    host = pk.host, port = pk.port, fingerprint = pk.fingerprint, changed = pk.changed,
                    onConfirm = { viewModel.confirmHostKeyAndRetry() }
                )
            }

            // ② 预检报告 + ③ 安装计划
            state.preflight?.let { report ->
                PreflightCard(report)
            }
            state.plan?.let { plan ->
                PlanCard(
                    strategy = plan.strategy.name,
                    reason = plan.strategyReason,
                    actions = plan.actions.map { it.title },
                    warnings = plan.warnings
                )
                if (state.phase == ProvisionPhase.IDLE && !state.completed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedButton(onClick = { viewModel.reset() }, modifier = Modifier.weight(1f)) {
                            Text("重新填写")
                        }
                        Button(onClick = { viewModel.executePlan() }, modifier = Modifier.weight(1f)) {
                            Text("一键安装")
                        }
                    }
                }
            }

            // ④ 安装进度
            if (state.steps.isNotEmpty()) {
                ProgressCard(steps = state.steps, log = state.currentLog, phase = state.phase)
            }

            // ⑤ 完成
            if (state.completed) {
                CompletedCard(onEnable = { viewModel.enableCloudEnvironment() })
            }

            // 错误
            state.error?.let { err ->
                Snackbar(
                    modifier = Modifier.padding(Spacing.sm),
                    action = { androidx.compose.material3.TextButton(onClick = { viewModel.reset() }) { Text("重试") } }
                ) { Text(err) }
            }

            // 进行中显示取消
            if (state.phase == ProvisionPhase.PREFLIGHT ||
                state.phase == ProvisionPhase.INSTALL_PREREQUISITES ||
                state.phase == ProvisionPhase.INSTALL_AI_CONTAINER) {
                TextButtonCenter(text = "取消") { viewModel.cancel() }
            }
        }
    }
}

@Composable
private fun StepIndicator(phase: ProvisionPhase) {
    val labels = listOf("填写服务器", "预检", "安装", "完成")
    val current = when (phase) {
        ProvisionPhase.IDLE -> 0
        ProvisionPhase.PREFLIGHT -> 1
        ProvisionPhase.INSTALL_PREREQUISITES, ProvisionPhase.INSTALL_AI_CONTAINER -> 2
        ProvisionPhase.FINISHED -> 3
        ProvisionPhase.FAILED -> 2
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        labels.forEachIndexed { i, label ->
            val done = i <= current
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(Radius.pill),
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerForm(
    form: CloudProvisionViewModel.ProvisionForm,
    loginKeys: List<com.minime.code.feature.agent.domain.container.SshLoginKey>,
    onFormChange: ((CloudProvisionViewModel.ProvisionForm) -> CloudProvisionViewModel.ProvisionForm) -> Unit,
    onPreflight: () -> Unit,
    canSubmit: Boolean
) {
    var keyExpanded by remember { mutableStateOf(false) }
    Text("连接你的云服务器，一键配置 AI 运行环境", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text("应用将先统一检查服务器环境，再依次安装前置依赖与 AI 容器；所有命令都通过 SSH 在你自己的服务器上执行。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.semanticColors.cardSurface)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> onFormChange { f -> f.copy(name = v) } },
                label = { Text("名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.host,
                onValueChange = { v -> onFormChange { f -> f.copy(host = v) } },
                label = { Text("服务器地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例：123.45.67.89") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = form.port,
                    onValueChange = { v -> onFormChange { f -> f.copy(port = v) } },
                    label = { Text("端口") }, singleLine = true, modifier = Modifier.weight(0.35f)
                )
                OutlinedTextField(
                    value = form.username,
                    onValueChange = { v -> onFormChange { f -> f.copy(username = v) } },
                    label = { Text("用户名") }, singleLine = true, modifier = Modifier.weight(0.65f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("使用 SSH 私钥", modifier = Modifier.weight(1f))
                Switch(form.useKey, { v -> onFormChange { f -> f.copy(useKey = v) } })
            }
            if (form.useKey) {
                val selectedName = loginKeys.firstOrNull { it.path == form.keyPath }?.name ?: ""
                ExposedDropdownMenuBox(
                    expanded = keyExpanded,
                    onExpandedChange = { keyExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("选择已导入的 SSH 密钥") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = keyExpanded) },
                        placeholder = { Text("请先在「主机密钥」中导入私钥") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = keyExpanded, onDismissRequest = { keyExpanded = false }) {
                        if (loginKeys.isEmpty()) {
                            DropdownMenuItem(text = { Text("无密钥，请先在设置中导入") }, onClick = { keyExpanded = false })
                        } else {
                            loginKeys.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(key.name) },
                                    onClick = {
                                        onFormChange { f -> f.copy(keyPath = key.path, keyPassphrase = key.passphrase.orEmpty()) }
                                        keyExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = form.password,
                    onValueChange = { v -> onFormChange { f -> f.copy(password = v) } },
                    label = { Text("密码") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = form.workspacePath,
                onValueChange = { v -> onFormChange { f -> f.copy(workspacePath = v) } },
                label = { Text("工作区路径") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.imageName,
                onValueChange = { v -> onFormChange { f -> f.copy(imageName = v) } },
                label = { Text("AI 运行环境镜像") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(com.minime.code.feature.workspace.domain.remote.CloudProvisioner.DEFAULT_IMAGE + " 或你的私有镜像") }
            )
            Text("默认拉取已发布的公开基座镜像；国内不可达时自动用 Release 离线包恢复。可填自建/私有镜像（私有需先 docker login）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Button(onClick = onPreflight, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
        Text("开始检查并安装")
    }
}

@Composable
private fun PreflightCard(report: com.minime.code.feature.workspace.domain.remote.PreflightReport) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.semanticColors.cardSurface)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("环境检查结果", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("${report.osName} · ${report.arch} · ${report.cpuCores} 核 · 可用磁盘 ${report.diskFreeMb} MB",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            report.items.forEach { item -> PreflightRow(item) }
        }
    }
}

@Composable
private fun PreflightRow(item: PreflightItem) {
    val color = when (item.status) {
        PreflightItem.Status.OK -> MaterialTheme.semanticColors.success
        PreflightItem.Status.MISSING -> MaterialTheme.colorScheme.error
        PreflightItem.Status.WARNING -> MaterialTheme.semanticColors.warning
        PreflightItem.Status.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(Spacing.sm))
        Text(item.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlanCard(strategy: String, reason: String, actions: List<String>, warnings: List<String>) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.semanticColors.cardSurface)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("安装计划", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("交付方式：$strategy（$reason）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            actions.forEachIndexed { i, a -> Text("${i + 1}. $a", style = MaterialTheme.typography.bodySmall) }
            warnings.forEach { w ->
                Text("⚠ $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.semanticColors.warning)
            }
        }
    }
}

@Composable
private fun HostKeyCard(host: String, port: Int, fingerprint: String, changed: Boolean, onConfirm: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.semanticColors.cardSurface)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(if (changed) "服务器指纹已变化" else "首次连接服务器", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("$host:$port", style = MaterialTheme.typography.bodyMedium)
            Text(fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("请核对指纹与服务器上 ssh-keygen -lf 的输出一致后再继续。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("确认并继续") }
        }
    }
}

@Composable
private fun ProgressCard(steps: List<ProvisionStep>, log: String, phase: ProvisionPhase) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.semanticColors.cardSurface)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            steps.forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (step.state) {
                        ProvisionStep.State.RUNNING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        ProvisionStep.State.DONE -> MiniMeIcon(MiniMeIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        ProvisionStep.State.FAILED -> MiniMeIcon(MiniMeIcons.XCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        else -> Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)))
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Text(step.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (log.isNotBlank()) {
                Text("—— 实时输出 ——", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(log.takeLast(2000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.sm)).padding(Spacing.sm))
            }
        }
    }
}

@Composable
private fun CompletedCard(onEnable: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.semanticColors.successContainer)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("✅ 云端环境已就绪", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("点击下面按钮保存配置并切换到云端模式，AI 的命令与文件操作将在你的云服务器上执行。",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) { Text("保存并切换到云端") }
        }
    }
}

@Composable
private fun TextButtonCenter(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}
