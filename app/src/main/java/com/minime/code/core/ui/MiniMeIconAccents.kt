package com.minime.code.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * MiniMe 图标彩色点缀层注册表（由 scripts/icons/gen_accents.py 生成，勿手改）。
 *
 * 每个条目 = 主体图标名 -> 点缀部件列表。点缀部件与主体 path 同几何，
 * 渲染时以语义槽位色（随主题预设/明暗/动态取色自动变化）覆盖主体描边。
 * 主体保持黑白（随 tint 反转），点缀独立取色 —— 双层渲染实现主题自适应。
 */
enum class MiniMeAccentSlot {
    PRIMARY, SECONDARY, TERTIARY, SUCCESS, WARNING, ERROR, INFO
}

/** 单个点缀部件：同几何重描的矢量 + 语义槽位。 */
data class MiniMeAccentPart(
    val vector: ImageVector,
    val slot: MiniMeAccentSlot
)

object MiniMeIconAccents {
    private fun accentVector(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) { block() }
        }.build()

    val registry: Map<String, List<MiniMeAccentPart>> by lazy {
        mapOf(
        "Activity" to listOf(
            MiniMeAccentPart(accentVector("Activity.accent0") {
                moveTo(22f, 12f)
                lineToRelative(-4f, 0f)
                lineToRelative(-3f, 9f)
                lineToRelative(-6f, -18f)
                lineToRelative(-3f, 9f)
                lineToRelative(-4f, 0f)
            }, MiniMeAccentSlot.SUCCESS),
        ),
        "AlertCircle" to listOf(
            MiniMeAccentPart(accentVector("AlertCircle.accent1_2") {
                moveTo(12f, 8f)
                lineTo(12f, 12f)
                moveTo(12f, 16f)
                lineTo(12.01f, 16f)
            }, MiniMeAccentSlot.WARNING),
        ),
        "BarChart2" to listOf(
            MiniMeAccentPart(accentVector("BarChart2.accent0") {
                moveTo(18f, 20f)
                lineTo(18f, 10f)
            }, MiniMeAccentSlot.SECONDARY),
            MiniMeAccentPart(accentVector("BarChart2.accent1") {
                moveTo(12f, 20f)
                lineTo(12f, 4f)
            }, MiniMeAccentSlot.PRIMARY),
            MiniMeAccentPart(accentVector("BarChart2.accent2") {
                moveTo(6f, 20f)
                lineTo(6f, 14f)
            }, MiniMeAccentSlot.TERTIARY),
        ),
        "Bell" to listOf(
            MiniMeAccentPart(accentVector("Bell.accent1") {
                moveTo(13.73f, 21f)
                arcToRelative(2f, 2f, 0f, false, true, -3.46f, 0f)
            }, MiniMeAccentSlot.WARNING),
        ),
        "Camera" to listOf(
            MiniMeAccentPart(accentVector("Camera.accent1") {
                moveTo(12f, 13f)
                moveToRelative(-4f, 0f)
                arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
                arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
            }, MiniMeAccentSlot.SECONDARY),
        ),
        "CheckCircle" to listOf(
            MiniMeAccentPart(accentVector("CheckCircle.accent1") {
                moveTo(22f, 4f)
                lineToRelative(-10f, 10.01f)
                lineToRelative(-3f, -3f)
            }, MiniMeAccentSlot.SUCCESS),
        ),
        "CheckSquare" to listOf(
            MiniMeAccentPart(accentVector("CheckSquare.accent0") {
                moveTo(9f, 11f)
                lineToRelative(3f, 3f)
                lineToRelative(10f, -10f)
            }, MiniMeAccentSlot.SUCCESS),
        ),
        "Clipboard" to listOf(
            MiniMeAccentPart(accentVector("Clipboard.accent1") {
                moveTo(9f, 2f)
                lineTo(15f, 2f)
                arcTo(1f, 1f, 0f, false, true, 16f, 3f)
                lineTo(16f, 5f)
                arcTo(1f, 1f, 0f, false, true, 15f, 6f)
                lineTo(9f, 6f)
                arcTo(1f, 1f, 0f, false, true, 8f, 5f)
                lineTo(8f, 3f)
                arcTo(1f, 1f, 0f, false, true, 9f, 2f)
                close()
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Clock" to listOf(
            MiniMeAccentPart(accentVector("Clock.accent1") {
                moveTo(12f, 6f)
                lineToRelative(0f, 6f)
                lineToRelative(4f, 2f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Cpu" to listOf(
            MiniMeAccentPart(accentVector("Cpu.accent1") {
                moveTo(9f, 9f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(-6f)
                close()
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Download" to listOf(
            MiniMeAccentPart(accentVector("Download.accent1_2") {
                moveTo(7f, 10f)
                lineToRelative(5f, 5f)
                lineToRelative(5f, -5f)
                moveTo(12f, 15f)
                lineTo(12f, 3f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "DownloadCloud" to listOf(
            MiniMeAccentPart(accentVector("DownloadCloud.accent0_1") {
                moveTo(8f, 17f)
                lineToRelative(4f, 4f)
                lineToRelative(4f, -4f)
                moveTo(12f, 12f)
                lineTo(12f, 21f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Eye" to listOf(
            MiniMeAccentPart(accentVector("Eye.accent1") {
                moveTo(12f, 12f)
                moveToRelative(-3f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "File" to listOf(
            MiniMeAccentPart(accentVector("File.accent1") {
                moveTo(13f, 2f)
                lineToRelative(0f, 7f)
                lineToRelative(7f, 0f)
            }, MiniMeAccentSlot.TERTIARY),
        ),
        "FilePlus" to listOf(
            MiniMeAccentPart(accentVector("FilePlus.accent2_3") {
                moveTo(12f, 18f)
                lineTo(12f, 12f)
                moveTo(9f, 15f)
                lineTo(15f, 15f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "FolderPlus" to listOf(
            MiniMeAccentPart(accentVector("FolderPlus.accent1_2") {
                moveTo(12f, 11f)
                lineTo(12f, 17f)
                moveTo(9f, 14f)
                lineTo(15f, 14f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "GitBranch" to listOf(
            MiniMeAccentPart(accentVector("GitBranch.accent1") {
                moveTo(18f, 6f)
                moveToRelative(-3f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "GitCommit" to listOf(
            MiniMeAccentPart(accentVector("GitCommit.accent0") {
                moveTo(12f, 12f)
                moveToRelative(-4f, 0f)
                arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
                arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "GitMerge" to listOf(
            MiniMeAccentPart(accentVector("GitMerge.accent0") {
                moveTo(18f, 18f)
                moveToRelative(-3f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            }, MiniMeAccentSlot.TERTIARY),
        ),
        "Globe" to listOf(
            MiniMeAccentPart(accentVector("Globe.accent2") {
                moveTo(12f, 2f)
                arcToRelative(15.3f, 15.3f, 0f, false, true, 4f, 10f)
                arcToRelative(15.3f, 15.3f, 0f, false, true, -4f, 10f)
                arcToRelative(15.3f, 15.3f, 0f, false, true, -4f, -10f)
                arcToRelative(15.3f, 15.3f, 0f, false, true, 4f, -10f)
                close()
            }, MiniMeAccentSlot.SECONDARY),
        ),
        "HardDrive" to listOf(
            MiniMeAccentPart(accentVector("HardDrive.accent2_3") {
                moveTo(6f, 16f)
                lineTo(6.01f, 16f)
                moveTo(10f, 16f)
                lineTo(10.01f, 16f)
            }, MiniMeAccentSlot.SUCCESS),
        ),
        "HelpCircle" to listOf(
            MiniMeAccentPart(accentVector("HelpCircle.accent1_2") {
                moveTo(9.09f, 9f)
                arcToRelative(3f, 3f, 0f, false, true, 5.83f, 1f)
                curveToRelative(0f, 2f, -3f, 3f, -3f, 3f)
                moveTo(12f, 17f)
                lineTo(12.01f, 17f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Image" to listOf(
            MiniMeAccentPart(accentVector("Image.accent1") {
                moveTo(8.5f, 8.5f)
                moveToRelative(-1.5f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
            }, MiniMeAccentSlot.WARNING),
        ),
        "Info" to listOf(
            MiniMeAccentPart(accentVector("Info.accent1_2") {
                moveTo(12f, 16f)
                lineTo(12f, 12f)
                moveTo(12f, 8f)
                lineTo(12.01f, 8f)
            }, MiniMeAccentSlot.INFO),
        ),
        "Package" to listOf(
            MiniMeAccentPart(accentVector("Package.accent2_3") {
                moveTo(3.27f, 6.96f)
                lineToRelative(8.73f, 5.05f)
                lineToRelative(8.73f, -5.05f)
                moveTo(12f, 22.08f)
                lineTo(12f, 12f)
            }, MiniMeAccentSlot.TERTIARY),
        ),
        "PieChart" to listOf(
            MiniMeAccentPart(accentVector("PieChart.accent1") {
                moveTo(22f, 12f)
                arcTo(10f, 10f, 0f, false, false, 12f, 2f)
                verticalLineToRelative(10f)
                close()
            }, MiniMeAccentSlot.WARNING),
        ),
        "Play" to listOf(
            MiniMeAccentPart(accentVector("Play.accent0") {
                moveTo(5f, 3f)
                lineToRelative(14f, 9f)
                lineToRelative(-14f, 9f)
                lineToRelative(0f, -18f)
                close()
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Power" to listOf(
            MiniMeAccentPart(accentVector("Power.accent1") {
                moveTo(12f, 2f)
                lineTo(12f, 12f)
            }, MiniMeAccentSlot.ERROR),
        ),
        "Save" to listOf(
            MiniMeAccentPart(accentVector("Save.accent1") {
                moveTo(17f, 21f)
                lineToRelative(0f, -8f)
                lineToRelative(-10f, 0f)
                lineToRelative(0f, 8f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Server" to listOf(
            MiniMeAccentPart(accentVector("Server.accent2_3") {
                moveTo(6f, 6f)
                lineTo(6.01f, 6f)
                moveTo(6f, 18f)
                lineTo(6.01f, 18f)
            }, MiniMeAccentSlot.SUCCESS),
        ),
        "Settings" to listOf(
            MiniMeAccentPart(accentVector("Settings.accent0") {
                moveTo(12f, 12f)
                moveToRelative(-3f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Share2" to listOf(
            MiniMeAccentPart(accentVector("Share2.accent0") {
                moveTo(18f, 5f)
                moveToRelative(-3f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            }, MiniMeAccentSlot.SECONDARY),
            MiniMeAccentPart(accentVector("Share2.accent1") {
                moveTo(6f, 12f)
                moveToRelative(-3f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            }, MiniMeAccentSlot.PRIMARY),
            MiniMeAccentPart(accentVector("Share2.accent2") {
                moveTo(18f, 19f)
                moveToRelative(-3f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            }, MiniMeAccentSlot.TERTIARY),
        ),
        "Smartphone" to listOf(
            MiniMeAccentPart(accentVector("Smartphone.accent1") {
                moveTo(12f, 18f)
                lineTo(12.01f, 18f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Sun" to listOf(
            MiniMeAccentPart(accentVector("Sun.accent0") {
                moveTo(12f, 12f)
                moveToRelative(-5f, 0f)
                arcToRelative(5f, 5f, 0f, true, true, 10f, 0f)
                arcToRelative(5f, 5f, 0f, true, true, -10f, 0f)
            }, MiniMeAccentSlot.WARNING),
        ),
        "Tablet" to listOf(
            MiniMeAccentPart(accentVector("Tablet.accent1") {
                moveTo(12f, 18f)
                lineTo(12.01f, 18f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Tag" to listOf(
            MiniMeAccentPart(accentVector("Tag.accent1") {
                moveTo(7f, 7f)
                lineTo(7.01f, 7f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Target" to listOf(
            MiniMeAccentPart(accentVector("Target.accent2") {
                moveTo(12f, 12f)
                moveToRelative(-2f, 0f)
                arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
                arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
            }, MiniMeAccentSlot.ERROR),
        ),
        "Terminal" to listOf(
            MiniMeAccentPart(accentVector("Terminal.accent0") {
                moveTo(4f, 17f)
                lineToRelative(6f, -6f)
                lineToRelative(-6f, -6f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Trash2" to listOf(
            MiniMeAccentPart(accentVector("Trash2.accent2_3") {
                moveTo(10f, 11f)
                lineTo(10f, 17f)
                moveTo(14f, 11f)
                lineTo(14f, 17f)
            }, MiniMeAccentSlot.ERROR),
        ),
        "Upload" to listOf(
            MiniMeAccentPart(accentVector("Upload.accent1_2") {
                moveTo(17f, 8f)
                lineToRelative(-5f, -5f)
                lineToRelative(-5f, 5f)
                moveTo(12f, 3f)
                lineTo(12f, 15f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "UploadCloud" to listOf(
            MiniMeAccentPart(accentVector("UploadCloud.accent0_1_3") {
                moveTo(16f, 16f)
                lineToRelative(-4f, -4f)
                lineToRelative(-4f, 4f)
                moveTo(12f, 12f)
                lineTo(12f, 21f)
                moveTo(16f, 16f)
                lineToRelative(-4f, -4f)
                lineToRelative(-4f, 4f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Video" to listOf(
            MiniMeAccentPart(accentVector("Video.accent0") {
                moveTo(23f, 7f)
                lineToRelative(-7f, 5f)
                lineToRelative(7f, 5f)
                lineToRelative(0f, -10f)
                close()
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "XCircle" to listOf(
            MiniMeAccentPart(accentVector("XCircle.accent1_2") {
                moveTo(15f, 9f)
                lineTo(9f, 15f)
                moveTo(9f, 9f)
                lineTo(15f, 15f)
            }, MiniMeAccentSlot.ERROR),
        ),
        "Zap" to listOf(
            MiniMeAccentPart(accentVector("Zap.accent0") {
                moveTo(13f, 2f)
                lineToRelative(-10f, 12f)
                lineToRelative(9f, 0f)
                lineToRelative(-1f, 8f)
                lineToRelative(10f, -12f)
                lineToRelative(-9f, 0f)
                lineToRelative(1f, -8f)
                close()
            }, MiniMeAccentSlot.WARNING),
        ),
        )
    }

    /** 查询某图标的点缀部件（无点缀返回 null）。 */
    fun forIcon(name: String?): List<MiniMeAccentPart>? = if (name == null) null else registry[name]
}
