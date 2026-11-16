#!/usr/bin/env python3
"""生成图标双层渲染预览页（HTML + SVG 模拟 Compose 双层渲染），
再用 Chromium 截图供几何/配色人工校验。
"""
import json
import os
import re

HERE = os.path.dirname(__file__)
paths = json.load(open(os.path.join(HERE, "paths.json"), encoding="utf-8"))

# 与 gen_accents.py 保持一致的点缀表
import importlib.util
spec = importlib.util.spec_from_file_location("gen_accents", os.path.join(HERE, "gen_accents.py"))
# 手动解析 ACCENTS，避免执行整个生成脚本
src = open(os.path.join(HERE, "gen_accents.py"), encoding="utf-8").read()
ns = {}
exec(re.search(r"ACCENTS = \{.*?\n\}", src, re.S).group(0), ns)
ACCENTS = ns["ACCENTS"]

CMD = {
    "moveTo": "M", "moveToRelative": "m",
    "lineTo": "L", "lineToRelative": "l",
    "horizontalLineTo": "H", "horizontalLineToRelative": "h",
    "verticalLineTo": "V", "verticalLineToRelative": "v",
    "curveTo": "C", "curveToRelative": "c",
    "reflectiveCurveTo": "S", "reflectiveCurveToRelative": "s",
    "quadTo": "Q", "quadToRelative": "q",
    "reflectiveQuadTo": "T", "reflectiveQuadToRelative": "t",
    "arcTo": "A", "arcToRelative": "a",
    "close": "Z",
}

def to_svg_path(cmds):
    # paths.json 中 body 是 path 块列表（每块为命令列表），先展平
    if cmds and isinstance(cmds[0], list):
        cmds = [c for blk in cmds for c in blk]
    out = []
    for c in cmds:
        fn, args = c.split("(", 1)
        args = args.rstrip(")").replace("f", "")
        if fn == "close":
            out.append("Z")
        else:
            out.append(CMD[fn] + args)
    return " ".join(out)

THEMES = [
    ("默认·浅色", "#1F2937", "#F8FAFC", dict(PRIMARY="#2563EB", SECONDARY="#0284C7", TERTIARY="#16A34A",
        SUCCESS="#22C55E", WARNING="#F59E0B", INFO="#0284C7", ERROR="#DC2626")),
    ("默认·深色", "#E5E7EB", "#111827", dict(PRIMARY="#60A5FA", SECONDARY="#7DD3FC", TERTIARY="#22C55E",
        SUCCESS="#4ADE80", WARNING="#FBBF24", INFO="#38BDF8", ERROR="#F87171")),
    ("咖啡·浅色", "#141413", "#FCFBF7", dict(PRIMARY="#C15F3C", SECONDARY="#6A9BCC", TERTIARY="#788C5D",
        SUCCESS="#22C55E", WARNING="#F59E0B", INFO="#0284C7", ERROR="#DC2626")),
    ("咖啡·深色", "#FAF9F5", "#232220", dict(PRIMARY="#E08A6B", SECONDARY="#8FB4DB", TERTIARY="#9AAF7C",
        SUCCESS="#4ADE80", WARNING="#FBBF24", INFO="#38BDF8", ERROR="#F87171")),
    ("石墨·浅色", "#17191C", "#FBFBFC", dict(PRIMARY="#3F4650", SECONDARY="#6B7280", TERTIARY="#0E7490",
        SUCCESS="#22C55E", WARNING="#F59E0B", INFO="#0284C7", ERROR="#DC2626")),
    ("石墨·深色", "#ECEDEF", "#191B1F", dict(PRIMARY="#B9C0CC", SECONDARY="#8B93A0", TERTIARY="#22D3EE",
        SUCCESS="#4ADE80", WARNING="#FBBF24", INFO="#38BDF8", ERROR="#F87171")),
    ("森语·浅色", "#12211A", "#FBFDFB", dict(PRIMARY="#15803D", SECONDARY="#0F766E", TERTIARY="#B45309",
        SUCCESS="#22C55E", WARNING="#F59E0B", INFO="#0284C7", ERROR="#DC2626")),
    ("森语·深色", "#E6F2E9", "#12201A", dict(PRIMARY="#4ADE80", SECONDARY="#2DD4BF", TERTIARY="#FBBF24",
        SUCCESS="#4ADE80", WARNING="#FBBF24", INFO="#38BDF8", ERROR="#F87171")),
]

def icon_svg(name, body, accent):
    parts = []
    parts.append(f'<path d="{to_svg_path(paths[name])}" fill="none" stroke="{body}" '
                 f'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>')
    if name in ACCENTS:
        for part in ACCENTS[name]:
            idxs, slot = part[0], part[1]
            width = part[2] if len(part) > 2 else 2
            d = " ".join(to_svg_path(paths[name][i]) for i in idxs)
            parts.append(f'<path d="{d}" fill="none" stroke="{accent[slot]}" '
                         f'stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"/>')
    return (f'<svg viewBox="0 0 24 24" width="28" height="28">{"".join(parts)}</svg>')

html = ['<html><head><meta charset="utf-8"><style>',
        "body{font-family:system-ui;margin:16px;background:#fff}",
        "h2{font-size:14px;margin:18px 0 6px}",
        ".row{display:flex;flex-wrap:wrap;gap:4px}",
        ".cell{width:96px;text-align:center;padding:6px 0;border-radius:6px}",
        ".cell small{display:block;font-size:8px;opacity:.7;margin-top:2px}",
        "</style></head><body>",
        "<h1>MiniMe 图标：黑白主体 + 彩色点缀（44 图标点缀版预览）</h1>"]

for tname, on, bg, slots in THEMES:
    html.append(f'<h2>{tname}</h2><div class="row" style="background:{bg}">')
    for name in sorted(ACCENTS):
        html.append(f'<div class="cell" style="color:{on}">{icon_svg(name, on, slots)}'
                    f'<small>{name}</small></div>')
    html.append("</div>")

# 纯黑白图标抽查（无点缀）
mono = [n for n in sorted(paths) if n not in ACCENTS][:24]
html.append('<h2>无点缀图标抽查（保持纯黑白）</h2><div class="row" style="background:#F8FAFC">')
for name in mono:
    html.append(f'<div class="cell" style="color:#1F2937">{icon_svg(name, "#1F2937", {})}'
                f'<small>{name}</small></div>')
html.append("</div></body></html>")

out = os.path.join(HERE, "preview.html")
open(out, "w", encoding="utf-8").write("\n".join(html))
print(f"预览页已生成: {out}")
