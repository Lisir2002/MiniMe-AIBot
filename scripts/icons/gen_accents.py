#!/usr/bin/env python3
"""生成 MiniMeIconAccents.kt —— 图标彩色点缀层注册表。

设计原则：
- 主体层（MiniMeIcons.kt）保持不动，渲染时随 tint 自动黑白。
- 点缀层 = 从主体层复制的同几何 path，用语义槽位色重描（完全覆盖主体描边）。
- 跨主题自适应：PRIMARY/SECONDARY/TERTIARY 随 4 套预设 + 动态取色变化，
  SUCCESS/WARNING/INFO 随明暗切换（FunctionalHues 跨预设稳定）。
"""

# 点缀表：icon -> [(主体 path 下标列表, 槽位)]
# 未列出的图标保持纯黑白（无点缀），约占一半，保证界面不花。
ACCENTS = {
    # 状态反馈
    "CheckCircle":   [([1], "SUCCESS")],
    "CheckSquare":   [([0], "SUCCESS")],
    "XCircle":       [([1, 2], "ERROR")],
    "AlertCircle":   [([1, 2], "WARNING")],
    "Info":          [([1, 2], "INFO")],
    "HelpCircle":    [([1, 2], "PRIMARY")],
    # 删除/新建
    "Trash2":        [([2, 3], "ERROR")],
    "FolderPlus":    [([1, 2], "PRIMARY")],
    "FilePlus":      [([2, 3], "PRIMARY")],
    "File":          [([1], "TERTIARY")],
    # 网络/终端/版本控制
    "Globe":         [([2], "SECONDARY")],
    "Terminal":      [([0], "PRIMARY")],
    "GitBranch":     [([1], "PRIMARY")],
    "GitCommit":     [([0], "PRIMARY")],
    "GitMerge":      [([0], "TERTIARY")],
    # 能量/状态
    "Zap":           [([0], "WARNING")],
    "Activity":      [([0], "SUCCESS")],
    "Bell":          [([1], "WARNING")],
    "Clock":         [([1], "PRIMARY")],
    # 媒体
    "Camera":        [([1], "SECONDARY")],
    "Eye":           [([1], "PRIMARY")],
    "Image":         [([1], "WARNING")],
    "Video":         [([0], "PRIMARY")],
    "Play":          [([0], "PRIMARY")],
    # 传输
    "Download":      [([1, 2], "PRIMARY")],
    "Upload":        [([1, 2], "PRIMARY")],
    "DownloadCloud": [([0, 1], "PRIMARY")],
    "UploadCloud":   [([0, 1, 3], "PRIMARY")],
    # 系统/存储
    "Save":          [([1], "PRIMARY")],
    "Server":        [([2, 3], "SUCCESS")],
    "HardDrive":     [([2, 3], "SUCCESS")],
    "Cpu":           [([1], "PRIMARY")],
    "Package":       [([2, 3], "TERTIARY")],
    "Power":         [([1], "ERROR")],
    # 设置/数据
    "Settings":      [([0], "PRIMARY")],
    "Tag":           [([1], "PRIMARY")],
    "Clipboard":     [([1], "PRIMARY")],
    "Target":        [([2], "ERROR")],
    "PieChart":      [([1], "WARNING")],
    "Sun":           [([0], "WARNING")],
    "Smartphone":    [([1], "PRIMARY")],
    "Tablet":        [([1], "PRIMARY")],
    # 多彩点缀（多 path 多槽位）
    "BarChart2":     [([0], "SECONDARY"), ([1], "PRIMARY"), ([2], "TERTIARY")],
    "Share2":        [([0], "SECONDARY"), ([1], "PRIMARY"), ([2], "TERTIARY")],
}

HEADER = '''package com.minime.code.core.ui

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
'''

FOOTER = '''    }

    /** 查询某图标的点缀部件（无点缀返回 null）。 */
    fun forIcon(name: String?): List<MiniMeAccentPart>? = if (name == null) null else registry[name]
}
'''

import json
import os

HERE = os.path.dirname(__file__)
paths = json.load(open(os.path.join(HERE, "paths.json"), encoding="utf-8"))

# 校验：点缀引用的下标必须存在
errors = []
for icon, parts in ACCENTS.items():
    if icon not in paths:
        errors.append(f"{icon}: 不存在于主体图标")
        continue
    for idxs, _slot in parts:
        for i in idxs:
            if i >= len(paths[icon]):
                errors.append(f"{icon}: path 下标 {i} 越界（共 {len(paths[icon])} 块）")
if errors:
    raise SystemExit("\n".join(errors))

entries = []
for icon in sorted(ACCENTS):
    parts = ACCENTS[icon]
    lines = [f'        "{icon}" to listOf(']
    for idxs, slot in parts:
        vec_name = f"{icon}.accent{chr(97 + len([p for p in parts]) and 0)}{''.join(str(i) for i in idxs)}"
        vec_name = f"{icon}.accent" + "_".join(str(i) for i in idxs)
        lines.append(f"            MiniMeAccentPart(accentVector(\"{vec_name}\") {{")
        for i in idxs:
            for cmd in paths[icon][i]:
                fn, args = cmd.split("(", 1)
                lines.append(f"                {fn}({args}")
        lines.append("            }, MiniMeAccentSlot." + slot + "),")
    lines.append("        ),")
    entries.append("\n".join(lines))

out = HEADER + "\n".join(entries) + "\n        )\n" + FOOTER
dest = os.path.join(HERE, "..", "..", "app/src/main/java/com/minime/code/core/ui/MiniMeIconAccents.kt")
open(dest, "w", encoding="utf-8").write(out)
print(f"生成完成: {os.path.abspath(dest)}  点缀图标数={len(ACCENTS)}")
