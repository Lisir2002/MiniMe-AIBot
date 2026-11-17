package com.minime.code.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minime.code.R
import com.minime.code.core.theme.Radius
import com.minime.code.core.theme.Spacing
import com.minime.code.core.theme.semanticColors
import com.minime.code.core.ui.MiniMeIcon
import com.minime.code.core.ui.MiniMeIcons

/**
 * 「运行与连接」总览枢纽页：把原来的「容器与镜像 / 连接与同步 / 完全云端 Agent」三个入口合并。
 *
 * 本页只做导航和状态概览，不搬动三个成熟页面的实现：
 * - 「本机运行」→ 进入现有容器与镜像页；
 * - 「服务器与云端」→ 进入合并后的服务器 + 云端 Agent 页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeConnectionScreen(
    cloudEnabled: Boolean,
    serverCount: Int,
    onOpenLocal: () -> Unit,
    onOpenCloud: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = { Text(stringResource(R.string.runtime_connection_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        MiniMeIcon(
                            MiniMeIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // 说明卡
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.md),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.md),
                    verticalAlignment = Alignment.Top
                ) {
                    MiniMeIcon(
                        MiniMeIcons.Globe,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        stringResource(R.string.runtime_connection_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // 本机运行卡
            RuntimeModeCard(
                icon = MiniMeIcons.HardDrive,
                title = stringResource(R.string.runtime_local_title),
                desc = stringResource(R.string.runtime_local_desc),
                badge = null,
                onClick = onOpenLocal
            )

            // 服务器与云端卡
            RuntimeModeCard(
                icon = MiniMeIcons.Cloud,
                title = stringResource(R.string.runtime_cloud_title),
                desc = stringResource(R.string.runtime_cloud_desc),
                badge = buildString {
                    if (cloudEnabled) append(stringResource(R.string.runtime_cloud_badge))
                    if (serverCount > 0) {
                        if (isNotEmpty()) append(" · ")
                        append(serverCount)
                    }
                }.takeIf { it.isNotBlank() },
                onClick = onOpenCloud
            )
        }
    }
}

@Composable
private fun RuntimeModeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    badge: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniMeIcon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleSmall)
                    if (badge != null) {
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.semanticColors.success
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            MiniMeIcon(
                MiniMeIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
