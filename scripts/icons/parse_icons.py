#!/usr/bin/env python3
"""解析 MiniMeIcons.kt，提取每个图标的 path 块（DSL 命令序列），输出 JSON。

输出结构: {icon_name: [ [ "moveTo(20,6)", "lineToRelative(-11,11)", ... ], ... ]}
供点缀层设计工具引用：点缀层可引用主体 path 的下标做同几何重着色。
"""
import json
import re
import sys

SRC = __import__("os").path.join(__import__("os").path.dirname(__file__), "..", "..", "app/src/main/java/com/minime/code/core/ui/MiniMeIcons.kt")

def main():
    text = open(SRC, encoding="utf-8").read()
    icons = {}
    # 按 "val X: ImageVector by lazy {" 分段
    pat = re.compile(r"^    val (\w+): ImageVector by lazy \{", re.M)
    matches = list(pat.finditer(text))
    for i, m in enumerate(matches):
        name = m.group(1)
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        block = text[start:end]
        # 提取每个 path(...) { ... } 块内的命令行（12 空格缩进）
        paths = []
        for pm in re.finditer(r"^        path\(\n((?:.*\n)*?)^        \}", block, re.M):
            body = pm.group(1)
            cmds = re.findall(r"^            (\w+)\((.*?)\)\s*$", body, re.M)
            paths.append([f"{fn}({args})" for fn, args in cmds])
        icons[name] = paths
    json.dump(icons, open(sys.argv[1] if len(sys.argv) > 1 else "paths.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=0)
    total_paths = sum(len(v) for v in icons.values())
    print(f"icons={len(icons)} total_path_blocks={total_paths}")

if __name__ == "__main__":
    main()
