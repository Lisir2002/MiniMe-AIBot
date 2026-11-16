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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.minime.code.feature.settings.presentation.CloudAgentSettingsViewModel

/** 完全云端 Agent 设置页：模式开关 + 服务器地址 + 令牌 + 连接测试。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAgentSettingsScreen(
    viewModel: CloudAgentSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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

            // 服务器地址
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = { viewModel.setServerUrl(it) },
                label = { Text(stringResource(R.string.cloud_agent_server_url)) },
                placeholder = { Text(stringResource(R.string.cloud_agent_server_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 令牌
            OutlinedTextField(
                value = state.token,
                onValueChange = { viewModel.setToken(it) },
                label = { Text(stringResource(R.string.cloud_agent_token)) },
                placeholder = { Text(stringResource(R.string.cloud_agent_token_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

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

            Spacer(Modifier.height(Spacing.sm))

            Text(
                stringResource(R.string.cloud_agent_setup_guide),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
