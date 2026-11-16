package com.minime.code.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.minime.code.core.theme.semanticColors

/**
 * MiniMe 双层图标：黑白主体 + 彩色点缀。
 *
 * - 主体层沿用 Material3 Icon 的 tint 机制：默认跟随 LocalContentColor，
 *   随浅色/深色主题自动黑白反转，禁用态 tint 透明度同样生效。
 * - 点缀层独立取色：PRIMARY/SECONDARY/TERTIARY 随主题预设与动态取色变化，
 *   SUCCESS/WARNING/INFO/ERROR 使用语义色（跨预设稳定色相、随明暗切换）。
 * - 点缀的透明度自动跟随 tint 透明度（禁用态整体淡出）。
 *
 * 参数签名与 Material3 Icon 保持一致，可直接替换调用。
 *
 * @param accentEnabled 传 false 可强制关闭该处点缀（如放在彩色按钮内部时）。
 */
@Composable
fun MiniMeIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    accentEnabled: Boolean = true,
) {
    val accentParts = remember(imageVector, accentEnabled) {
        if (accentEnabled) MiniMeIconAccents.forIcon(imageVector.name) else null
    }
    if (accentParts.isNullOrEmpty()) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint
        )
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.semanticColors
    val accentAlpha = if (tint == Color.Unspecified || tint.alpha >= 1f) 1f else tint.alpha

    fun accentColor(slot: MiniMeAccentSlot): Color {
        val base = when (slot) {
            MiniMeAccentSlot.PRIMARY -> colorScheme.primary
            MiniMeAccentSlot.SECONDARY -> colorScheme.secondary
            MiniMeAccentSlot.TERTIARY -> colorScheme.tertiary
            MiniMeAccentSlot.SUCCESS -> semantic.success
            MiniMeAccentSlot.WARNING -> semantic.warning
            MiniMeAccentSlot.INFO -> semantic.info
            MiniMeAccentSlot.ERROR -> colorScheme.error
        }
        return base.copy(alpha = base.alpha * accentAlpha)
    }

    Box(modifier = modifier) {
        // 主体层：不设 modifier，由 24dp 固有尺寸（或 Box 约束）撑开整个组件，
        // 避免全 matchParentSize 导致无显式尺寸时塌缩为 0x0 不可见
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier,
            tint = tint
        )
        // 点缀层：同几何重描，语义槽位色
        accentParts.forEach { part ->
            Icon(
                imageVector = part.vector,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                tint = accentColor(part.slot)
            )
        }
    }
}
