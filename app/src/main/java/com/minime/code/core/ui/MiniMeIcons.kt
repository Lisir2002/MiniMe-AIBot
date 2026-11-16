package com.minime.code.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * MiniMe-Code 全局图标库（SVG path 自绘）。
 *
 * 图标由 Feather 风格的矢量 path 精确还原而来，全部使用 24x24 视口、2f 描边、圆角端点。
 * 图标本身不硬编码语义色（stroke 用中性黑模板），实际颜色由调用处 Icon 的 tint 决定，
 * 默认跟随 LocalContentColor，因此可自适应浅色/深色主题及各按钮状态。
 */
object MiniMeIcons {
    val Check: ImageVector by lazy {
        ImageVector.Builder(
            name = "Check",
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
        ) {
            moveTo(20f, 6f)
            lineToRelative(-11f, 11f)
            lineToRelative(-5f, -5f)
        }
        }.build()
    }

    val Plus: ImageVector by lazy {
        ImageVector.Builder(
            name = "Plus",
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
        ) {
            moveTo(12f, 5f)
            lineTo(12f, 19f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5f, 12f)
            lineTo(19f, 12f)
        }
        }.build()
    }

    val Trash2: ImageVector by lazy {
        ImageVector.Builder(
            name = "Trash2",
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
        ) {
            moveTo(3f, 6f)
            lineToRelative(2f, 0f)
            lineToRelative(16f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(19f, 6f)
            verticalLineToRelative(14f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(7f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(6f)
            moveToRelative(3f, 0f)
            verticalLineTo(4f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(4f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineToRelative(2f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 11f)
            lineTo(10f, 17f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(14f, 11f)
            lineTo(14f, 17f)
        }
        }.build()
    }

    val X: ImageVector by lazy {
        ImageVector.Builder(
            name = "X",
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
        ) {
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
        }
        }.build()
    }

    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChevronRight",
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
        ) {
            moveTo(9f, 18f)
            lineToRelative(6f, -6f)
            lineToRelative(-6f, -6f)
        }
        }.build()
    }

    val ArrowLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowLeft",
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
        ) {
            moveTo(19f, 12f)
            lineTo(5f, 12f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 19f)
            lineToRelative(-7f, -7f)
            lineToRelative(7f, -7f)
        }
        }.build()
    }

    val FileText: ImageVector by lazy {
        ImageVector.Builder(
            name = "FileText",
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
        ) {
            moveTo(14f, 2f)
            horizontalLineTo(6f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
            verticalLineToRelative(16f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
            horizontalLineToRelative(12f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
            verticalLineTo(8f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(14f, 2f)
            lineToRelative(0f, 6f)
            lineToRelative(6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(16f, 13f)
            lineTo(8f, 13f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(16f, 17f)
            lineTo(8f, 17f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 9f)
            lineToRelative(-1f, 0f)
            lineToRelative(-1f, 0f)
        }
        }.build()
    }

    val Copy: ImageVector by lazy {
        ImageVector.Builder(
            name = "Copy",
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
        ) {
            moveTo(11f, 9f)
            lineTo(20f, 9f)
            arcTo(2f, 2f, 0f, false, true, 22f, 11f)
            lineTo(22f, 20f)
            arcTo(2f, 2f, 0f, false, true, 20f, 22f)
            lineTo(11f, 22f)
            arcTo(2f, 2f, 0f, false, true, 9f, 20f)
            lineTo(9f, 11f)
            arcTo(2f, 2f, 0f, false, true, 11f, 9f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5f, 15f)
            horizontalLineTo(4f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(4f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(9f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineToRelative(1f)
        }
        }.build()
    }

    val RefreshCw: ImageVector by lazy {
        ImageVector.Builder(
            name = "RefreshCw",
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
        ) {
            moveTo(23f, 4f)
            lineToRelative(0f, 6f)
            lineToRelative(-6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(1f, 20f)
            lineToRelative(0f, -6f)
            lineToRelative(6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3.51f, 9f)
            arcToRelative(9f, 9f, 0f, false, true, 14.85f, -3.36f)
            lineTo(23f, 10f)
            moveTo(1f, 14f)
            lineToRelative(4.64f, 4.36f)
            arcTo(9f, 9f, 0f, false, false, 20.49f, 15f)
        }
        }.build()
    }

    val Folder: ImageVector by lazy {
        ImageVector.Builder(
            name = "Folder",
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
        ) {
            moveTo(22f, 19f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(4f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(5f)
            lineToRelative(2f, 3f)
            horizontalLineToRelative(9f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            close()
        }
        }.build()
    }

    val Globe: ImageVector by lazy {
        ImageVector.Builder(
            name = "Globe",
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
        ) {
            moveTo(12f, 12f)
            moveToRelative(-10f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(2f, 12f)
            lineTo(22f, 12f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 2f)
            arcToRelative(15.3f, 15.3f, 0f, false, true, 4f, 10f)
            arcToRelative(15.3f, 15.3f, 0f, false, true, -4f, 10f)
            arcToRelative(15.3f, 15.3f, 0f, false, true, -4f, -10f)
            arcToRelative(15.3f, 15.3f, 0f, false, true, 4f, -10f)
            close()
        }
        }.build()
    }

    val EyeOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "EyeOff",
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
        ) {
            moveTo(17.94f, 17.94f)
            arcTo(10.07f, 10.07f, 0f, false, true, 12f, 20f)
            curveToRelative(-7f, 0f, -11f, -8f, -11f, -8f)
            arcToRelative(18.45f, 18.45f, 0f, false, true, 5.06f, -5.94f)
            moveTo(9.9f, 4.24f)
            arcTo(9.12f, 9.12f, 0f, false, true, 12f, 4f)
            curveToRelative(7f, 0f, 11f, 8f, 11f, 8f)
            arcToRelative(18.5f, 18.5f, 0f, false, true, -2.16f, 3.19f)
            moveToRelative(-6.72f, -1.07f)
            arcToRelative(3f, 3f, 0f, true, true, -4.24f, -4.24f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(1f, 1f)
            lineTo(23f, 23f)
        }
        }.build()
    }

    val Eye: ImageVector by lazy {
        ImageVector.Builder(
            name = "Eye",
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
        ) {
            moveTo(1f, 12f)
            reflectiveCurveToRelative(4f, -8f, 11f, -8f)
            reflectiveCurveToRelative(11f, 8f, 11f, 8f)
            reflectiveCurveToRelative(-4f, 8f, -11f, 8f)
            reflectiveCurveToRelative(-11f, -8f, -11f, -8f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 12f)
            moveToRelative(-3f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        }
        }.build()
    }

    val AlertCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "AlertCircle",
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
        ) {
            moveTo(12f, 12f)
            moveToRelative(-10f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 8f)
            lineTo(12f, 12f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 16f)
            lineTo(12.01f, 16f)
        }
        }.build()
    }

    val Terminal: ImageVector by lazy {
        ImageVector.Builder(
            name = "Terminal",
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
        ) {
            moveTo(4f, 17f)
            lineToRelative(6f, -6f)
            lineToRelative(-6f, -6f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 19f)
            lineTo(20f, 19f)
        }
        }.build()
    }

    val Key: ImageVector by lazy {
        ImageVector.Builder(
            name = "Key",
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
        ) {
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
        }
        }.build()
    }

    val GitBranch: ImageVector by lazy {
        ImageVector.Builder(
            name = "GitBranch",
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
        ) {
            moveTo(6f, 3f)
            lineTo(6f, 15f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(18f, 6f)
            moveToRelative(-3f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 18f)
            moveToRelative(-3f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(18f, 9f)
            arcToRelative(9f, 9f, 0f, false, true, -9f, 9f)
        }
        }.build()
    }

    val Zap: ImageVector by lazy {
        ImageVector.Builder(
            name = "Zap",
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
        ) {
            moveTo(13f, 2f)
            lineToRelative(-10f, 12f)
            lineToRelative(9f, 0f)
            lineToRelative(-1f, 8f)
            lineToRelative(10f, -12f)
            lineToRelative(-9f, 0f)
            lineToRelative(1f, -8f)
            close()
        }
        }.build()
    }

    val Image: ImageVector by lazy {
        ImageVector.Builder(
            name = "Image",
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
        ) {
            moveTo(5f, 3f)
            lineTo(19f, 3f)
            arcTo(2f, 2f, 0f, false, true, 21f, 5f)
            lineTo(21f, 19f)
            arcTo(2f, 2f, 0f, false, true, 19f, 21f)
            lineTo(5f, 21f)
            arcTo(2f, 2f, 0f, false, true, 3f, 19f)
            lineTo(3f, 5f)
            arcTo(2f, 2f, 0f, false, true, 5f, 3f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(8.5f, 8.5f)
            moveToRelative(-1.5f, 0f)
            arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f)
            arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 15f)
            lineToRelative(-5f, -5f)
            lineToRelative(-11f, 11f)
        }
        }.build()
    }

    val HardDrive: ImageVector by lazy {
        ImageVector.Builder(
            name = "HardDrive",
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
        ) {
            moveTo(22f, 12f)
            lineTo(2f, 12f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5.45f, 5.11f)
            lineTo(2f, 12f)
            verticalLineToRelative(6f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
            horizontalLineToRelative(16f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
            verticalLineToRelative(-6f)
            lineToRelative(-3.45f, -6.89f)
            arcTo(2f, 2f, 0f, false, false, 16.76f, 4f)
            horizontalLineTo(7.24f)
            arcToRelative(2f, 2f, 0f, false, false, -1.79f, 1.11f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 16f)
            lineTo(6.01f, 16f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 16f)
            lineTo(10.01f, 16f)
        }
        }.build()
    }

    val Edit2: ImageVector by lazy {
        ImageVector.Builder(
            name = "Edit2",
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
        ) {
            moveTo(17f, 3f)
            arcToRelative(2.828f, 2.828f, 0f, true, true, 4f, 4f)
            lineTo(7.5f, 20.5f)
            lineTo(2f, 22f)
            lineToRelative(1.5f, -5.5f)
            lineTo(17f, 3f)
            close()
        }
        }.build()
    }

    val Shield: ImageVector by lazy {
        ImageVector.Builder(
            name = "Shield",
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
        ) {
            moveTo(12f, 22f)
            reflectiveCurveToRelative(8f, -4f, 8f, -10f)
            verticalLineTo(5f)
            lineToRelative(-8f, -3f)
            lineToRelative(-8f, 3f)
            verticalLineToRelative(7f)
            curveToRelative(0f, 6f, 8f, 10f, 8f, 10f)
            close()
        }
        }.build()
    }

    val Download: ImageVector by lazy {
        ImageVector.Builder(
            name = "Download",
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
        ) {
            moveTo(21f, 15f)
            verticalLineToRelative(4f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineToRelative(-4f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7f, 10f)
            lineToRelative(5f, 5f)
            lineToRelative(5f, -5f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 15f)
            lineTo(12f, 3f)
        }
        }.build()
    }

    val Sliders: ImageVector by lazy {
        ImageVector.Builder(
            name = "Sliders",
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
        ) {
            moveTo(4f, 21f)
            lineTo(4f, 14f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4f, 10f)
            lineTo(4f, 3f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 21f)
            lineTo(12f, 12f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 8f)
            lineTo(12f, 3f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20f, 21f)
            lineTo(20f, 16f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20f, 12f)
            lineTo(20f, 3f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(1f, 14f)
            lineTo(7f, 14f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 8f)
            lineTo(15f, 8f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(17f, 16f)
            lineTo(23f, 16f)
        }
        }.build()
    }

    val Server: ImageVector by lazy {
        ImageVector.Builder(
            name = "Server",
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
        ) {
            moveTo(4f, 2f)
            lineTo(20f, 2f)
            arcTo(2f, 2f, 0f, false, true, 22f, 4f)
            lineTo(22f, 8f)
            arcTo(2f, 2f, 0f, false, true, 20f, 10f)
            lineTo(4f, 10f)
            arcTo(2f, 2f, 0f, false, true, 2f, 8f)
            lineTo(2f, 4f)
            arcTo(2f, 2f, 0f, false, true, 4f, 2f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4f, 14f)
            lineTo(20f, 14f)
            arcTo(2f, 2f, 0f, false, true, 22f, 16f)
            lineTo(22f, 20f)
            arcTo(2f, 2f, 0f, false, true, 20f, 22f)
            lineTo(4f, 22f)
            arcTo(2f, 2f, 0f, false, true, 2f, 20f)
            lineTo(2f, 16f)
            arcTo(2f, 2f, 0f, false, true, 4f, 14f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 6f)
            lineTo(6.01f, 6f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 18f)
            lineTo(6.01f, 18f)
        }
        }.build()
    }

    val Search: ImageVector by lazy {
        ImageVector.Builder(
            name = "Search",
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
        ) {
            moveTo(11f, 11f)
            moveToRelative(-8f, 0f)
            arcToRelative(8f, 8f, 0f, true, true, 16f, 0f)
            arcToRelative(8f, 8f, 0f, true, true, -16f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 21f)
            lineTo(16.65f, 16.65f)
        }
        }.build()
    }

    val RotateCcw: ImageVector by lazy {
        ImageVector.Builder(
            name = "RotateCcw",
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
        ) {
            moveTo(1f, 4f)
            lineToRelative(0f, 6f)
            lineToRelative(6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3.51f, 15f)
            arcToRelative(9f, 9f, 0f, true, false, 2.13f, -9.36f)
            lineTo(1f, 10f)
        }
        }.build()
    }

    val Edit3: ImageVector by lazy {
        ImageVector.Builder(
            name = "Edit3",
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
        ) {
            moveTo(12f, 20f)
            horizontalLineToRelative(9f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(16.5f, 3.5f)
            arcToRelative(2.121f, 2.121f, 0f, false, true, 3f, 3f)
            lineTo(7f, 19f)
            lineToRelative(-4f, 1f)
            lineToRelative(1f, -4f)
            lineTo(16.5f, 3.5f)
            close()
        }
        }.build()
    }

    val Cpu: ImageVector by lazy {
        ImageVector.Builder(
            name = "Cpu",
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
        ) {
            moveTo(6f, 4f)
            lineTo(18f, 4f)
            arcTo(2f, 2f, 0f, false, true, 20f, 6f)
            lineTo(20f, 18f)
            arcTo(2f, 2f, 0f, false, true, 18f, 20f)
            lineTo(6f, 20f)
            arcTo(2f, 2f, 0f, false, true, 4f, 18f)
            lineTo(4f, 6f)
            arcTo(2f, 2f, 0f, false, true, 6f, 4f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 9f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-6f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 1f)
            lineTo(9f, 4f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(15f, 1f)
            lineTo(15f, 4f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 20f)
            lineTo(9f, 23f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(15f, 20f)
            lineTo(15f, 23f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20f, 9f)
            lineTo(23f, 9f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20f, 14f)
            lineTo(23f, 14f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(1f, 9f)
            lineTo(4f, 9f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(1f, 14f)
            lineTo(4f, 14f)
        }
        }.build()
    }

    val UploadCloud: ImageVector by lazy {
        ImageVector.Builder(
            name = "UploadCloud",
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
        ) {
            moveTo(16f, 16f)
            lineToRelative(-4f, -4f)
            lineToRelative(-4f, 4f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 12f)
            lineTo(12f, 21f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20.39f, 18.39f)
            arcTo(5f, 5f, 0f, false, false, 18f, 9f)
            horizontalLineToRelative(-1.26f)
            arcTo(8f, 8f, 0f, true, false, 3f, 16.3f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(16f, 16f)
            lineToRelative(-4f, -4f)
            lineToRelative(-4f, 4f)
        }
        }.build()
    }

    val Info: ImageVector by lazy {
        ImageVector.Builder(
            name = "Info",
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
        ) {
            moveTo(12f, 12f)
            moveToRelative(-10f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 16f)
            lineTo(12f, 12f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 8f)
            lineTo(12.01f, 8f)
        }
        }.build()
    }

    val Github: ImageVector by lazy {
        ImageVector.Builder(
            name = "Github",
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
        ) {
            moveTo(9f, 19f)
            curveToRelative(-5f, 1.5f, -5f, -2.5f, -7f, -3f)
            moveToRelative(14f, 6f)
            verticalLineToRelative(-3.87f)
            arcToRelative(3.37f, 3.37f, 0f, false, false, -0.94f, -2.61f)
            curveToRelative(3.14f, -0.35f, 6.44f, -1.54f, 6.44f, -7f)
            arcTo(5.44f, 5.44f, 0f, false, false, 20f, 4.77f)
            arcTo(5.07f, 5.07f, 0f, false, false, 19.91f, 1f)
            reflectiveCurveTo(18.73f, 0.65f, 16f, 2.48f)
            arcToRelative(13.38f, 13.38f, 0f, false, false, -7f, 0f)
            curveTo(6.27f, 0.65f, 5.09f, 1f, 5.09f, 1f)
            arcTo(5.07f, 5.07f, 0f, false, false, 5f, 4.77f)
            arcToRelative(5.44f, 5.44f, 0f, false, false, -1.5f, 3.78f)
            curveToRelative(0f, 5.42f, 3.3f, 6.61f, 6.44f, 7f)
            arcTo(3.37f, 3.37f, 0f, false, false, 9f, 18.13f)
            verticalLineTo(22f)
        }
        }.build()
    }

    val ExternalLink: ImageVector by lazy {
        ImageVector.Builder(
            name = "ExternalLink",
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
        ) {
            moveTo(18f, 13f)
            verticalLineToRelative(6f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(8f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(6f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(15f, 3f)
            lineToRelative(6f, 0f)
            lineToRelative(0f, 6f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 14f)
            lineTo(21f, 3f)
        }
        }.build()
    }

    val ChevronDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChevronDown",
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
        ) {
            moveTo(6f, 9f)
            lineToRelative(6f, 6f)
            lineToRelative(6f, -6f)
        }
        }.build()
    }

    val CheckSquare: ImageVector by lazy {
        ImageVector.Builder(
            name = "CheckSquare",
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
        ) {
            moveTo(9f, 11f)
            lineToRelative(3f, 3f)
            lineToRelative(10f, -10f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 12f)
            verticalLineToRelative(7f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(11f)
        }
        }.build()
    }

    val Tag: ImageVector by lazy {
        ImageVector.Builder(
            name = "Tag",
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
        ) {
            moveTo(20.59f, 13.41f)
            lineToRelative(-7.17f, 7.17f)
            arcToRelative(2f, 2f, 0f, false, true, -2.83f, 0f)
            lineTo(2f, 12f)
            verticalLineTo(2f)
            horizontalLineToRelative(10f)
            lineToRelative(8.59f, 8.59f)
            arcToRelative(2f, 2f, 0f, false, true, 0f, 2.82f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7f, 7f)
            lineTo(7.01f, 7f)
        }
        }.build()
    }

    val Tablet: ImageVector by lazy {
        ImageVector.Builder(
            name = "Tablet",
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
        ) {
            moveTo(6f, 2f)
            lineTo(18f, 2f)
            arcTo(2f, 2f, 0f, false, true, 20f, 4f)
            lineTo(20f, 20f)
            arcTo(2f, 2f, 0f, false, true, 18f, 22f)
            lineTo(6f, 22f)
            arcTo(2f, 2f, 0f, false, true, 4f, 20f)
            lineTo(4f, 4f)
            arcTo(2f, 2f, 0f, false, true, 6f, 2f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 18f)
            lineTo(12.01f, 18f)
        }
        }.build()
    }

    val Smartphone: ImageVector by lazy {
        ImageVector.Builder(
            name = "Smartphone",
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
        ) {
            moveTo(7f, 2f)
            lineTo(17f, 2f)
            arcTo(2f, 2f, 0f, false, true, 19f, 4f)
            lineTo(19f, 20f)
            arcTo(2f, 2f, 0f, false, true, 17f, 22f)
            lineTo(7f, 22f)
            arcTo(2f, 2f, 0f, false, true, 5f, 20f)
            lineTo(5f, 4f)
            arcTo(2f, 2f, 0f, false, true, 7f, 2f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 18f)
            lineTo(12.01f, 18f)
        }
        }.build()
    }

    val Settings: ImageVector by lazy {
        ImageVector.Builder(
            name = "Settings",
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
        ) {
            moveTo(12f, 12f)
            moveToRelative(-3f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(19.4f, 15f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 0.33f, 1.82f)
            lineToRelative(0.06f, 0.06f)
            arcToRelative(2f, 2f, 0f, false, true, 0f, 2.83f)
            arcToRelative(2f, 2f, 0f, false, true, -2.83f, 0f)
            lineToRelative(-0.06f, -0.06f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -1.82f, -0.33f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -1f, 1.51f)
            verticalLineTo(21f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineToRelative(-0.09f)
            arcTo(1.65f, 1.65f, 0f, false, false, 9f, 19.4f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -1.82f, 0.33f)
            lineToRelative(-0.06f, 0.06f)
            arcToRelative(2f, 2f, 0f, false, true, -2.83f, 0f)
            arcToRelative(2f, 2f, 0f, false, true, 0f, -2.83f)
            lineToRelative(0.06f, -0.06f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 0.33f, -1.82f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -1.51f, -1f)
            horizontalLineTo(3f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(0.09f)
            arcTo(1.65f, 1.65f, 0f, false, false, 4.6f, 9f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -0.33f, -1.82f)
            lineToRelative(-0.06f, -0.06f)
            arcToRelative(2f, 2f, 0f, false, true, 0f, -2.83f)
            arcToRelative(2f, 2f, 0f, false, true, 2.83f, 0f)
            lineToRelative(0.06f, 0.06f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 1.82f, 0.33f)
            horizontalLineTo(9f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 1f, -1.51f)
            verticalLineTo(3f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineToRelative(0.09f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 1f, 1.51f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 1.82f, -0.33f)
            lineToRelative(0.06f, -0.06f)
            arcToRelative(2f, 2f, 0f, false, true, 2.83f, 0f)
            arcToRelative(2f, 2f, 0f, false, true, 0f, 2.83f)
            lineToRelative(-0.06f, 0.06f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -0.33f, 1.82f)
            verticalLineTo(9f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 1.51f, 1f)
            horizontalLineTo(21f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineToRelative(-0.09f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -1.51f, 1f)
            close()
        }
        }.build()
    }

    val Play: ImageVector by lazy {
        ImageVector.Builder(
            name = "Play",
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
        ) {
            moveTo(5f, 3f)
            lineToRelative(14f, 9f)
            lineToRelative(-14f, 9f)
            lineToRelative(0f, -18f)
            close()
        }
        }.build()
    }

    val Cloud: ImageVector by lazy {
        ImageVector.Builder(
            name = "Cloud",
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
        ) {
            moveTo(18f, 10f)
            horizontalLineToRelative(-1.26f)
            arcTo(8f, 8f, 0f, true, false, 9f, 20f)
            horizontalLineToRelative(9f)
            arcToRelative(5f, 5f, 0f, false, false, 0f, -10f)
            close()
        }
        }.build()
    }

    val Book: ImageVector by lazy {
        ImageVector.Builder(
            name = "Book",
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
        ) {
            moveTo(4f, 19.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 6.5f, 17f)
            horizontalLineTo(20f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6.5f, 2f)
            horizontalLineTo(20f)
            verticalLineToRelative(20f)
            horizontalLineTo(6.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 4f, 19.5f)
            verticalLineToRelative(-15f)
            arcTo(2.5f, 2.5f, 0f, false, true, 6.5f, 2f)
            close()
        }
        }.build()
    }

    val Users: ImageVector by lazy {
        ImageVector.Builder(
            name = "Users",
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
        ) {
            moveTo(17f, 21f)
            verticalLineToRelative(-2f)
            arcToRelative(4f, 4f, 0f, false, false, -4f, -4f)
            horizontalLineTo(5f)
            arcToRelative(4f, 4f, 0f, false, false, -4f, 4f)
            verticalLineToRelative(2f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 7f)
            moveToRelative(-4f, 0f)
            arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
            arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(23f, 21f)
            verticalLineToRelative(-2f)
            arcToRelative(4f, 4f, 0f, false, false, -3f, -3.87f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(16f, 3.13f)
            arcToRelative(4f, 4f, 0f, false, true, 0f, 7.75f)
        }
        }.build()
    }

    val MoreHorizontal: ImageVector by lazy {
        ImageVector.Builder(
            name = "MoreHorizontal",
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
        ) {
            moveTo(12f, 12f)
            moveToRelative(-1f, 0f)
            arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
            arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(19f, 12f)
            moveToRelative(-1f, 0f)
            arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
            arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5f, 12f)
            moveToRelative(-1f, 0f)
            arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
            arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
        }
        }.build()
    }

    val MessageSquare: ImageVector by lazy {
        ImageVector.Builder(
            name = "MessageSquare",
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
        ) {
            moveTo(21f, 15f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(7f)
            lineToRelative(-4f, 4f)
            verticalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(14f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            close()
        }
        }.build()
    }

    val Menu: ImageVector by lazy {
        ImageVector.Builder(
            name = "Menu",
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
        ) {
            moveTo(3f, 12f)
            lineTo(21f, 12f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 6f)
            lineTo(21f, 6f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 18f)
            lineTo(21f, 18f)
        }
        }.build()
    }

    val GitCommit: ImageVector by lazy {
        ImageVector.Builder(
            name = "GitCommit",
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
        ) {
            moveTo(12f, 12f)
            moveToRelative(-4f, 0f)
            arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
            arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(1.05f, 12f)
            lineTo(7f, 12f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(17.01f, 12f)
            lineTo(22.96f, 12f)
        }
        }.build()
    }

    val Database: ImageVector by lazy {
        ImageVector.Builder(
            name = "Database",
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
        ) {
            moveTo(3f, 5f)
            arcToRelative(9f, 3f, 0f, true, false, 18f, 0f)
            arcToRelative(9f, 3f, 0f, true, false, -18f, 0f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 12f)
            curveToRelative(0f, 1.66f, -4f, 3f, -9f, 3f)
            reflectiveCurveToRelative(-9f, -1.34f, -9f, -3f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 5f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.66f, 4f, 3f, 9f, 3f)
            reflectiveCurveToRelative(9f, -1.34f, 9f, -3f)
            verticalLineTo(5f)
        }
        }.build()
    }

    val Code: ImageVector by lazy {
        ImageVector.Builder(
            name = "Code",
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
        ) {
            moveTo(16f, 18f)
            lineToRelative(6f, -6f)
            lineToRelative(-6f, -6f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(8f, 6f)
            lineToRelative(-6f, 6f)
            lineToRelative(6f, 6f)
        }
        }.build()
    }

    val Archive: ImageVector by lazy {
        ImageVector.Builder(
            name = "Archive",
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
        ) {
            moveTo(21f, 8f)
            lineToRelative(0f, 13f)
            lineToRelative(-18f, 0f)
            lineToRelative(0f, -13f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(1f, 3f)
            horizontalLineToRelative(22f)
            verticalLineToRelative(5f)
            horizontalLineToRelative(-22f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 12f)
            lineTo(14f, 12f)
        }
        }.build()
    }

    val Tool: ImageVector by lazy {
        ImageVector.Builder(
            name = "Tool",
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
        ) {
            moveTo(14.7f, 6.3f)
            arcToRelative(1f, 1f, 0f, false, false, 0f, 1.4f)
            lineToRelative(1.6f, 1.6f)
            arcToRelative(1f, 1f, 0f, false, false, 1.4f, 0f)
            lineToRelative(3.77f, -3.77f)
            arcToRelative(6f, 6f, 0f, false, true, -7.94f, 7.94f)
            lineToRelative(-6.91f, 6.91f)
            arcToRelative(2.12f, 2.12f, 0f, false, true, -3f, -3f)
            lineToRelative(6.91f, -6.91f)
            arcToRelative(6f, 6f, 0f, false, true, 7.94f, -7.94f)
            lineToRelative(-3.76f, 3.76f)
            close()
        }
        }.build()
    }

    val Sun: ImageVector by lazy {
        ImageVector.Builder(
            name = "Sun",
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
        ) {
            moveTo(12f, 12f)
            moveToRelative(-5f, 0f)
            arcToRelative(5f, 5f, 0f, true, true, 10f, 0f)
            arcToRelative(5f, 5f, 0f, true, true, -10f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 1f)
            lineTo(12f, 3f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 21f)
            lineTo(12f, 23f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4.22f, 4.22f)
            lineTo(5.64f, 5.64f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(18.36f, 18.36f)
            lineTo(19.78f, 19.78f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(1f, 12f)
            lineTo(3f, 12f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 12f)
            lineTo(23f, 12f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4.22f, 19.78f)
            lineTo(5.64f, 18.36f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(18.36f, 5.64f)
            lineTo(19.78f, 4.22f)
        }
        }.build()
    }

    val Save: ImageVector by lazy {
        ImageVector.Builder(
            name = "Save",
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
        ) {
            moveTo(19f, 21f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(11f)
            lineToRelative(5f, 5f)
            verticalLineToRelative(11f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(17f, 21f)
            lineToRelative(0f, -8f)
            lineToRelative(-10f, 0f)
            lineToRelative(0f, 8f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7f, 3f)
            lineToRelative(0f, 5f)
            lineToRelative(8f, 0f)
        }
        }.build()
    }

    val Moon: ImageVector by lazy {
        ImageVector.Builder(
            name = "Moon",
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
        ) {
            moveTo(21f, 12.79f)
            arcTo(9f, 9f, 0f, true, true, 11.21f, 3f)
            arcTo(7f, 7f, 0f, false, false, 21f, 12.79f)
            close()
        }
        }.build()
    }

    val Monitor: ImageVector by lazy {
        ImageVector.Builder(
            name = "Monitor",
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
        ) {
            moveTo(4f, 3f)
            lineTo(20f, 3f)
            arcTo(2f, 2f, 0f, false, true, 22f, 5f)
            lineTo(22f, 15f)
            arcTo(2f, 2f, 0f, false, true, 20f, 17f)
            lineTo(4f, 17f)
            arcTo(2f, 2f, 0f, false, true, 2f, 15f)
            lineTo(2f, 5f)
            arcTo(2f, 2f, 0f, false, true, 4f, 3f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(8f, 21f)
            lineTo(16f, 21f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 17f)
            lineTo(12f, 21f)
        }
        }.build()
    }

    val Minus: ImageVector by lazy {
        ImageVector.Builder(
            name = "Minus",
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
        ) {
            moveTo(5f, 12f)
            lineTo(19f, 12f)
        }
        }.build()
    }

    val HelpCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "HelpCircle",
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
        ) {
            moveTo(12f, 12f)
            moveToRelative(-10f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9.09f, 9f)
            arcToRelative(3f, 3f, 0f, false, true, 5.83f, 1f)
            curveToRelative(0f, 2f, -3f, 3f, -3f, 3f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 17f)
            lineTo(12.01f, 17f)
        }
        }.build()
    }

    val File: ImageVector by lazy {
        ImageVector.Builder(
            name = "File",
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
        ) {
            moveTo(13f, 2f)
            horizontalLineTo(6f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
            verticalLineToRelative(16f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
            horizontalLineToRelative(12f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
            verticalLineTo(9f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(13f, 2f)
            lineToRelative(0f, 7f)
            lineToRelative(7f, 0f)
        }
        }.build()
    }

    val DownloadCloud: ImageVector by lazy {
        ImageVector.Builder(
            name = "DownloadCloud",
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
        ) {
            moveTo(8f, 17f)
            lineToRelative(4f, 4f)
            lineToRelative(4f, -4f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 12f)
            lineTo(12f, 21f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20.88f, 18.09f)
            arcTo(5f, 5f, 0f, false, false, 18f, 9f)
            horizontalLineToRelative(-1.26f)
            arcTo(8f, 8f, 0f, true, false, 3f, 16.29f)
        }
        }.build()
    }

    val Clipboard: ImageVector by lazy {
        ImageVector.Builder(
            name = "Clipboard",
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
        ) {
            moveTo(16f, 4f)
            horizontalLineToRelative(2f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineToRelative(14f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(6f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(6f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(2f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
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
        }
        }.build()
    }

    val ChevronLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChevronLeft",
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
        ) {
            moveTo(15f, 18f)
            lineToRelative(-6f, -6f)
            lineToRelative(6f, -6f)
        }
        }.build()
    }

    val Camera: ImageVector by lazy {
        ImageVector.Builder(
            name = "Camera",
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
        ) {
            moveTo(23f, 19f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(3f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(8f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(4f)
            lineToRelative(2f, -3f)
            horizontalLineToRelative(6f)
            lineToRelative(2f, 3f)
            horizontalLineToRelative(4f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 13f)
            moveToRelative(-4f, 0f)
            arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
            arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
        }
        }.build()
    }

    val Box: ImageVector by lazy {
        ImageVector.Builder(
            name = "Box",
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
        ) {
            moveTo(21f, 16f)
            verticalLineTo(8f)
            arcToRelative(2f, 2f, 0f, false, false, -1f, -1.73f)
            lineToRelative(-7f, -4f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, 0f)
            lineToRelative(-7f, 4f)
            arcTo(2f, 2f, 0f, false, false, 3f, 8f)
            verticalLineToRelative(8f)
            arcToRelative(2f, 2f, 0f, false, false, 1f, 1.73f)
            lineToRelative(7f, 4f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 0f)
            lineToRelative(7f, -4f)
            arcTo(2f, 2f, 0f, false, false, 21f, 16f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3.27f, 6.96f)
            lineToRelative(8.73f, 5.05f)
            lineToRelative(8.73f, -5.05f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 22.08f)
            lineTo(12f, 12f)
        }
        }.build()
    }

    val ArrowUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowUp",
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
        ) {
            moveTo(12f, 19f)
            lineTo(12f, 5f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5f, 12f)
            lineToRelative(7f, -7f)
            lineToRelative(7f, 7f)
        }
        }.build()
    }

    val ArrowDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowDown",
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
        ) {
            moveTo(12f, 5f)
            lineTo(12f, 19f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(19f, 12f)
            lineToRelative(-7f, 7f)
            lineToRelative(-7f, -7f)
        }
        }.build()
    }

    val Activity: ImageVector by lazy {
        ImageVector.Builder(
            name = "Activity",
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
        ) {
            moveTo(22f, 12f)
            lineToRelative(-4f, 0f)
            lineToRelative(-3f, 9f)
            lineToRelative(-6f, -18f)
            lineToRelative(-3f, 9f)
            lineToRelative(-4f, 0f)
        }
        }.build()
    }

    val XCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "XCircle",
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
        ) {
            moveTo(12f, 12f)
            moveToRelative(-10f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(15f, 9f)
            lineTo(9f, 15f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 9f)
            lineTo(15f, 15f)
        }
        }.build()
    }

    val Video: ImageVector by lazy {
        ImageVector.Builder(
            name = "Video",
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
        ) {
            moveTo(23f, 7f)
            lineToRelative(-7f, 5f)
            lineToRelative(7f, 5f)
            lineToRelative(0f, -10f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 5f)
            lineTo(14f, 5f)
            arcTo(2f, 2f, 0f, false, true, 16f, 7f)
            lineTo(16f, 17f)
            arcTo(2f, 2f, 0f, false, true, 14f, 19f)
            lineTo(3f, 19f)
            arcTo(2f, 2f, 0f, false, true, 1f, 17f)
            lineTo(1f, 7f)
            arcTo(2f, 2f, 0f, false, true, 3f, 5f)
            close()
        }
        }.build()
    }

    val User: ImageVector by lazy {
        ImageVector.Builder(
            name = "User",
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
        ) {
            moveTo(20f, 21f)
            verticalLineToRelative(-2f)
            arcToRelative(4f, 4f, 0f, false, false, -4f, -4f)
            horizontalLineTo(8f)
            arcToRelative(4f, 4f, 0f, false, false, -4f, 4f)
            verticalLineToRelative(2f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 7f)
            moveToRelative(-4f, 0f)
            arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
            arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
        }
        }.build()
    }

    val Upload: ImageVector by lazy {
        ImageVector.Builder(
            name = "Upload",
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
        ) {
            moveTo(21f, 15f)
            verticalLineToRelative(4f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineToRelative(-4f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(17f, 8f)
            lineToRelative(-5f, -5f)
            lineToRelative(-5f, 5f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 3f)
            lineTo(12f, 15f)
        }
        }.build()
    }

    val Type: ImageVector by lazy {
        ImageVector.Builder(
            name = "Type",
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
        ) {
            moveTo(4f, 7f)
            lineToRelative(0f, -3f)
            lineToRelative(16f, 0f)
            lineToRelative(0f, 3f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 20f)
            lineTo(15f, 20f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 4f)
            lineTo(12f, 20f)
        }
        }.build()
    }

    val Target: ImageVector by lazy {
        ImageVector.Builder(
            name = "Target",
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
        ) {
            moveTo(12f, 12f)
            moveToRelative(-10f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 12f)
            moveToRelative(-6f, 0f)
            arcToRelative(6f, 6f, 0f, true, true, 12f, 0f)
            arcToRelative(6f, 6f, 0f, true, true, -12f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 12f)
            moveToRelative(-2f, 0f)
            arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
            arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
        }
        }.build()
    }

    val Square: ImageVector by lazy {
        ImageVector.Builder(
            name = "Square",
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
        ) {
            moveTo(5f, 3f)
            lineTo(19f, 3f)
            arcTo(2f, 2f, 0f, false, true, 21f, 5f)
            lineTo(21f, 19f)
            arcTo(2f, 2f, 0f, false, true, 19f, 21f)
            lineTo(5f, 21f)
            arcTo(2f, 2f, 0f, false, true, 3f, 19f)
            lineTo(3f, 5f)
            arcTo(2f, 2f, 0f, false, true, 5f, 3f)
            close()
        }
        }.build()
    }

    val Share2: ImageVector by lazy {
        ImageVector.Builder(
            name = "Share2",
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
        ) {
            moveTo(18f, 5f)
            moveToRelative(-3f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 12f)
            moveToRelative(-3f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(18f, 19f)
            moveToRelative(-3f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(8.59f, 13.51f)
            lineTo(15.42f, 17.49f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(15.41f, 6.51f)
            lineTo(8.59f, 10.49f)
        }
        }.build()
    }

    val Power: ImageVector by lazy {
        ImageVector.Builder(
            name = "Power",
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
        ) {
            moveTo(18.36f, 6.64f)
            arcToRelative(9f, 9f, 0f, true, true, -12.73f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 2f)
            lineTo(12f, 12f)
        }
        }.build()
    }

    val PieChart: ImageVector by lazy {
        ImageVector.Builder(
            name = "PieChart",
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
        ) {
            moveTo(21.21f, 15.89f)
            arcTo(10f, 10f, 0f, true, true, 8f, 2.83f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(22f, 12f)
            arcTo(10f, 10f, 0f, false, false, 12f, 2f)
            verticalLineToRelative(10f)
            close()
        }
        }.build()
    }

    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "Pause",
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
        ) {
            moveTo(6f, 4f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(16f)
            horizontalLineToRelative(-4f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(14f, 4f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(16f)
            horizontalLineToRelative(-4f)
            close()
        }
        }.build()
    }

    val Package: ImageVector by lazy {
        ImageVector.Builder(
            name = "Package",
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
        ) {
            moveTo(16.5f, 9.4f)
            lineTo(7.5f, 4.21f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 16f)
            verticalLineTo(8f)
            arcToRelative(2f, 2f, 0f, false, false, -1f, -1.73f)
            lineToRelative(-7f, -4f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, 0f)
            lineToRelative(-7f, 4f)
            arcTo(2f, 2f, 0f, false, false, 3f, 8f)
            verticalLineToRelative(8f)
            arcToRelative(2f, 2f, 0f, false, false, 1f, 1.73f)
            lineToRelative(7f, 4f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 0f)
            lineToRelative(7f, -4f)
            arcTo(2f, 2f, 0f, false, false, 21f, 16f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3.27f, 6.96f)
            lineToRelative(8.73f, 5.05f)
            lineToRelative(8.73f, -5.05f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 22.08f)
            lineTo(12f, 12f)
        }
        }.build()
    }

    val Music: ImageVector by lazy {
        ImageVector.Builder(
            name = "Music",
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
        ) {
            moveTo(9f, 18f)
            verticalLineTo(5f)
            lineToRelative(12f, -2f)
            verticalLineToRelative(13f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 18f)
            moveToRelative(-3f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(18f, 16f)
            moveToRelative(-3f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        }
        }.build()
    }

    val Minimize2: ImageVector by lazy {
        ImageVector.Builder(
            name = "Minimize2",
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
        ) {
            moveTo(4f, 14f)
            lineToRelative(6f, 0f)
            lineToRelative(0f, 6f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20f, 10f)
            lineToRelative(-6f, 0f)
            lineToRelative(0f, -6f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(14f, 10f)
            lineTo(21f, 3f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 21f)
            lineTo(10f, 14f)
        }
        }.build()
    }

    val Minimize: ImageVector by lazy {
        ImageVector.Builder(
            name = "Minimize",
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
        ) {
            moveTo(8f, 3f)
            verticalLineToRelative(3f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(3f)
            moveToRelative(18f, 0f)
            horizontalLineToRelative(-3f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(3f)
            moveToRelative(0f, 18f)
            verticalLineToRelative(-3f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(3f)
            moveTo(3f, 16f)
            horizontalLineToRelative(3f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineToRelative(3f)
        }
        }.build()
    }

    val Maximize: ImageVector by lazy {
        ImageVector.Builder(
            name = "Maximize",
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
        ) {
            moveTo(8f, 3f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
            verticalLineToRelative(3f)
            moveToRelative(18f, 0f)
            verticalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, -2f)
            horizontalLineToRelative(-3f)
            moveToRelative(0f, 18f)
            horizontalLineToRelative(3f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
            verticalLineToRelative(-3f)
            moveTo(3f, 16f)
            verticalLineToRelative(3f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
            horizontalLineToRelative(3f)
        }
        }.build()
    }

    val Lock: ImageVector by lazy {
        ImageVector.Builder(
            name = "Lock",
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
        ) {
            moveTo(5f, 11f)
            lineTo(19f, 11f)
            arcTo(2f, 2f, 0f, false, true, 21f, 13f)
            lineTo(21f, 20f)
            arcTo(2f, 2f, 0f, false, true, 19f, 22f)
            lineTo(5f, 22f)
            arcTo(2f, 2f, 0f, false, true, 3f, 20f)
            lineTo(3f, 13f)
            arcTo(2f, 2f, 0f, false, true, 5f, 11f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7f, 11f)
            verticalLineTo(7f)
            arcToRelative(5f, 5f, 0f, false, true, 10f, 0f)
            verticalLineToRelative(4f)
        }
        }.build()
    }

    val GitMerge: ImageVector by lazy {
        ImageVector.Builder(
            name = "GitMerge",
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
        ) {
            moveTo(18f, 18f)
            moveToRelative(-3f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 6f)
            moveToRelative(-3f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 21f)
            verticalLineTo(9f)
            arcToRelative(9f, 9f, 0f, false, false, 9f, 9f)
        }
        }.build()
    }

    val FolderPlus: ImageVector by lazy {
        ImageVector.Builder(
            name = "FolderPlus",
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
        ) {
            moveTo(22f, 19f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(4f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(5f)
            lineToRelative(2f, 3f)
            horizontalLineToRelative(9f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 11f)
            lineTo(12f, 17f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 14f)
            lineTo(15f, 14f)
        }
        }.build()
    }

    val FilePlus: ImageVector by lazy {
        ImageVector.Builder(
            name = "FilePlus",
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
        ) {
            moveTo(14f, 2f)
            horizontalLineTo(6f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
            verticalLineToRelative(16f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
            horizontalLineToRelative(12f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
            verticalLineTo(8f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(14f, 2f)
            lineToRelative(0f, 6f)
            lineToRelative(6f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 18f)
            lineTo(12f, 12f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 15f)
            lineTo(15f, 15f)
        }
        }.build()
    }

    val Clock: ImageVector by lazy {
        ImageVector.Builder(
            name = "Clock",
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
        ) {
            moveTo(12f, 12f)
            moveToRelative(-10f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
            arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 6f)
            lineToRelative(0f, 6f)
            lineToRelative(4f, 2f)
        }
        }.build()
    }

    val ChevronUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChevronUp",
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
        ) {
            moveTo(18f, 15f)
            lineToRelative(-6f, -6f)
            lineToRelative(-6f, 6f)
        }
        }.build()
    }

    val CheckCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "CheckCircle",
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
        ) {
            moveTo(22f, 11.08f)
            verticalLineTo(12f)
            arcToRelative(10f, 10f, 0f, true, true, -5.93f, -9.14f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(22f, 4f)
            lineToRelative(-10f, 10.01f)
            lineToRelative(-3f, -3f)
        }
        }.build()
    }

    val BookOpen: ImageVector by lazy {
        ImageVector.Builder(
            name = "BookOpen",
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
        ) {
            moveTo(2f, 3f)
            horizontalLineToRelative(6f)
            arcToRelative(4f, 4f, 0f, false, true, 4f, 4f)
            verticalLineToRelative(14f)
            arcToRelative(3f, 3f, 0f, false, false, -3f, -3f)
            horizontalLineTo(2f)
            close()
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(22f, 3f)
            horizontalLineToRelative(-6f)
            arcToRelative(4f, 4f, 0f, false, false, -4f, 4f)
            verticalLineToRelative(14f)
            arcToRelative(3f, 3f, 0f, false, true, 3f, -3f)
            horizontalLineToRelative(7f)
            close()
        }
        }.build()
    }

    val Bell: ImageVector by lazy {
        ImageVector.Builder(
            name = "Bell",
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
        ) {
            moveTo(18f, 8f)
            arcTo(6f, 6f, 0f, false, false, 6f, 8f)
            curveToRelative(0f, 7f, -3f, 9f, -3f, 9f)
            horizontalLineToRelative(18f)
            reflectiveCurveToRelative(-3f, -2f, -3f, -9f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(13.73f, 21f)
            arcToRelative(2f, 2f, 0f, false, true, -3.46f, 0f)
        }
        }.build()
    }

    val BarChart2: ImageVector by lazy {
        ImageVector.Builder(
            name = "BarChart2",
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
        ) {
            moveTo(18f, 20f)
            lineTo(18f, 10f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 20f)
            lineTo(12f, 4f)
        }
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 20f)
            lineTo(6f, 14f)
        }
        }.build()
    }

    val Scissors: ImageVector by lazy {
        ImageVector.Builder(
            name = "Scissors",
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
        ) {
            moveTo(20f, 4f)
            lineTo(8.12f, 15.88f)
            moveTo(14.47f, 14.48f)
            lineTo(20f, 20f)
            moveTo(8.12f, 8.12f)
            lineTo(12f, 12f)
            moveTo(9f, 6f)
            arcTo(3f, 3f, 0f, true, true, 3f, 6f)
            arcTo(3f, 3f, 0f, true, true, 9f, 6f)
            moveTo(9f, 18f)
            arcTo(3f, 3f, 0f, true, true, 3f, 18f)
            arcTo(3f, 3f, 0f, true, true, 9f, 18f)
        }
        }.build()
    }

    val Pin: ImageVector by lazy {
        ImageVector.Builder(
            name = "Pin",
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
        ) {
            moveTo(15f, 10f)
            arcTo(3f, 3f, 0f, true, true, 9f, 10f)
            arcTo(3f, 3f, 0f, true, true, 15f, 10f)
            moveTo(21f, 10f)
            curveTo(21f, 17f, 12f, 23f, 12f, 23f)
            curveTo(6f, 19f, 3f, 14.67f, 3f, 10f)
            curveTo(4.09f, 7.71f, 6.98f, 2.11f, 9.41f, 1.38f)
        }
        }.build()
    }

    val Undo: ImageVector by lazy {
        ImageVector.Builder(
            name = "Undo",
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
        ) {
            moveTo(9f, 14f)
            lineTo(4f, 9f)
            lineTo(9f, 4f)
            moveTo(20f, 20f)
            verticalLineTo(13f)
            curveTo(18.11f, 15.41f, 12.37f, 16.49f, 15.39f, 16.95f)
            curveTo(18.42f, 17.42f, 20.83f, 14.45f, 19.74f, 11.58f)
            curveTo(18.66f, 8.72f, 14.89f, 8.09f, 12.93f, 10.44f)
            horizontalLineTo(4f)
        }
        }.build()
    }

    val Redo: ImageVector by lazy {
        ImageVector.Builder(
            name = "Redo",
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
        ) {
            moveTo(15f, 14f)
            lineTo(20f, 9f)
            lineTo(15f, 4f)
            moveTo(4f, 20f)
            verticalLineTo(13f)
            curveTo(4.35f, 12.68f, 6.07f, 9.46f, 6.51f, 9.29f)
            horizontalLineTo(20f)
        }
        }.build()
    }

}