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
    private fun accentVector(
        name: String,
        width: Float = 2f,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
    ): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = width,
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
        "Archive" to listOf(
            MiniMeAccentPart(accentVector("Archive.accent2") {
                moveTo(10f, 12f)
                lineTo(14f, 12f)
            }, MiniMeAccentSlot.PRIMARY),
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
        "BookOpen" to listOf(
            MiniMeAccentPart(accentVector("BookOpen.accent0") {
                moveTo(2f, 3f)
                horizontalLineToRelative(6f)
                arcToRelative(4f, 4f, 0f, false, true, 4f, 4f)
                verticalLineToRelative(14f)
                arcToRelative(3f, 3f, 0f, false, false, -3f, -3f)
                horizontalLineTo(2f)
                close()
            }, MiniMeAccentSlot.SECONDARY),
        ),
        "Box" to listOf(
            MiniMeAccentPart(accentVector("Box.accent1") {
                moveTo(3.27f, 6.96f)
                lineToRelative(8.73f, 5.05f)
                lineToRelative(8.73f, -5.05f)
            }, MiniMeAccentSlot.SECONDARY),
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
        "Cloud" to listOf(
            MiniMeAccentPart(accentVector("Cloud.accent0") {
                moveTo(18f, 10f)
                horizontalLineToRelative(-1.26f)
                arcTo(8f, 8f, 0f, true, false, 9f, 20f)
                horizontalLineToRelative(9f)
                arcToRelative(5f, 5f, 0f, false, false, 0f, -10f)
                close()
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Code" to listOf(
            MiniMeAccentPart(accentVector("Code.accent0") {
                moveTo(16f, 18f)
                lineToRelative(6f, -6f)
                lineToRelative(-6f, -6f)
            }, MiniMeAccentSlot.PRIMARY),
            MiniMeAccentPart(accentVector("Code.accent1") {
                moveTo(8f, 6f)
                lineToRelative(-6f, 6f)
                lineToRelative(6f, 6f)
            }, MiniMeAccentSlot.SECONDARY),
        ),
        "Copy" to listOf(
            MiniMeAccentPart(accentVector("Copy.accent1") {
                moveTo(5f, 15f)
                horizontalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                horizontalLineToRelative(9f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineToRelative(1f)
            }, MiniMeAccentSlot.SECONDARY),
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
        "Database" to listOf(
            MiniMeAccentPart(accentVector("Database.accent0") {
                moveTo(3f, 5f)
                arcToRelative(9f, 3f, 0f, true, false, 18f, 0f)
                arcToRelative(9f, 3f, 0f, true, false, -18f, 0f)
                close()
            }, MiniMeAccentSlot.SECONDARY),
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
        "Edit2" to listOf(
            MiniMeAccentPart(accentVector("Edit2.accent0") {
                moveTo(17f, 3f)
                arcToRelative(2.828f, 2.828f, 0f, true, true, 4f, 4f)
                lineTo(7.5f, 20.5f)
                lineTo(2f, 22f)
                lineToRelative(1.5f, -5.5f)
                lineTo(17f, 3f)
                close()
            }, MiniMeAccentSlot.SECONDARY),
        ),
        "Edit3" to listOf(
            MiniMeAccentPart(accentVector("Edit3.accent1") {
                moveTo(16.5f, 3.5f)
                arcToRelative(2.121f, 2.121f, 0f, false, true, 3f, 3f)
                lineTo(7f, 19f)
                lineToRelative(-4f, 1f)
                lineToRelative(1f, -4f)
                lineTo(16.5f, 3.5f)
                close()
            }, MiniMeAccentSlot.SECONDARY),
        ),
        "ExternalLink" to listOf(
            MiniMeAccentPart(accentVector("ExternalLink.accent1_2") {
                moveTo(15f, 3f)
                lineToRelative(6f, 0f)
                lineToRelative(0f, 6f)
                moveTo(10f, 14f)
                lineTo(21f, 3f)
            }, MiniMeAccentSlot.SECONDARY),
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
        "FileText" to listOf(
            MiniMeAccentPart(accentVector("FileText.accent2_3") {
                moveTo(16f, 13f)
                lineTo(8f, 13f)
                moveTo(16f, 17f)
                lineTo(8f, 17f)
            }, MiniMeAccentSlot.TERTIARY),
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
            MiniMeAccentPart(accentVector("HardDrive.accent2_3", width = 3.6f) {
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
        "Key" to listOf(
            MiniMeAccentPart(accentVector("Key.accent0") {
                moveTo(21f, 2f)
                lineToRelative(-2f, 2f)
                moveToRelative(-7.61f, 7.61f)
                arcToRelative(5.5f, 5.5f, 0f, true, true, -7.778f, 7.778f)
                arcToRelative(5.5f, 5.5f, 0f, false, true, 7.777f, -7.777f)
                close()
                moveTo(11.39f, 11.61f)
                lineTo(15.5f, 7.5f)
                moveToRelative(0f, 0f)
                lineToRelative(3f, 3f)
                lineTo(22f, 7f)
                lineToRelative(-3f, -3f)
                moveToRelative(-3.5f, 3.5f)
                lineTo(19f, 4f)
            }, MiniMeAccentSlot.WARNING),
        ),
        "Lock" to listOf(
            MiniMeAccentPart(accentVector("Lock.accent1") {
                moveTo(7f, 11f)
                verticalLineTo(7f)
                arcToRelative(5f, 5f, 0f, false, true, 10f, 0f)
                verticalLineToRelative(4f)
            }, MiniMeAccentSlot.WARNING),
        ),
        "MessageSquare" to listOf(
            MiniMeAccentPart(accentVector("MessageSquare.accent0") {
                moveTo(21f, 15f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(7f)
                lineToRelative(-4f, 4f)
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                horizontalLineToRelative(14f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                close()
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Music" to listOf(
            MiniMeAccentPart(accentVector("Music.accent1_2") {
                moveTo(6f, 18f)
                moveToRelative(-3f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
                moveTo(18f, 16f)
                moveToRelative(-3f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            }, MiniMeAccentSlot.PRIMARY),
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
        "Search" to listOf(
            MiniMeAccentPart(accentVector("Search.accent1") {
                moveTo(21f, 21f)
                lineTo(16.65f, 16.65f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Server" to listOf(
            MiniMeAccentPart(accentVector("Server.accent2_3", width = 3.6f) {
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
        "Shield" to listOf(
            MiniMeAccentPart(accentVector("Shield.accent0") {
                moveTo(12f, 22f)
                reflectiveCurveToRelative(8f, -4f, 8f, -10f)
                verticalLineTo(5f)
                lineToRelative(-8f, -3f)
                lineToRelative(-8f, 3f)
                verticalLineToRelative(7f)
                curveToRelative(0f, 6f, 8f, 10f, 8f, 10f)
                close()
            }, MiniMeAccentSlot.SUCCESS),
        ),
        "Sliders" to listOf(
            MiniMeAccentPart(accentVector("Sliders.accent7") {
                moveTo(9f, 8f)
                lineTo(15f, 8f)
            }, MiniMeAccentSlot.SECONDARY),
        ),
        "Smartphone" to listOf(
            MiniMeAccentPart(accentVector("Smartphone.accent1", width = 3.2f) {
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
            MiniMeAccentPart(accentVector("Tablet.accent1", width = 3.2f) {
                moveTo(12f, 18f)
                lineTo(12.01f, 18f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Tag" to listOf(
            MiniMeAccentPart(accentVector("Tag.accent1", width = 3.6f) {
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
        "User" to listOf(
            MiniMeAccentPart(accentVector("User.accent1") {
                moveTo(12f, 7f)
                moveToRelative(-4f, 0f)
                arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
                arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
            }, MiniMeAccentSlot.PRIMARY),
        ),
        "Users" to listOf(
            MiniMeAccentPart(accentVector("Users.accent1") {
                moveTo(9f, 7f)
                moveToRelative(-4f, 0f)
                arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
                arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
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
