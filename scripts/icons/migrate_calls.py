#!/usr/bin/env python3
"""把调用 material3.Icon(MiniMeIcons.X, ...) 的位置迁移为 MiniMeIcon(...)。

规则：
- (?<![A-Za-z0-9_])Icon\\(\\s*MiniMeIcons\\.            -> MiniMeIcon(MiniMeIcons.
- (?<![A-Za-z0-9_])Icon\\(\\s*imageVector\\s*=\\s*MiniMeIcons\\. -> MiniMeIcon(imageVector = MiniMeIcons.
- 为使用 MiniMeIcon 的文件补 import（同包跳过）。
- 不动 Icon(painter/bitmap) 与非 MiniMeIcons 的 Icon 调用。
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "app/src/main/java")
ROOT = os.path.abspath(ROOT)

changed_files = 0
total_calls = 0
manual_review = []

for dirpath, _dirs, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        text = open(path, encoding="utf-8").read()
        if "MiniMeIcons." not in text:
            continue

        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        pkg = pkg.group(1) if pkg else ""
        same_pkg = pkg == "com.minime.code.core.ui"

        orig = text
        # 命名参数形态（先行，避免被位置形态规则抢先匹配）
        text, n1 = re.subn(
            r"(?<![A-Za-z0-9_])Icon\(\s*imageVector\s*=\s*MiniMeIcons\.",
            "MiniMeIcon(imageVector = MiniMeIcons.", text)
        # 位置参数形态
        text, n2 = re.subn(
            r"(?<![A-Za-z0-9_])Icon\(\s*MiniMeIcons\.",
            "MiniMeIcon(MiniMeIcons.", text)
        n = n1 + n2
        if n == 0:
            continue

        uses = "MiniMeIcon(" in text
        if uses and not same_pkg and "import com.minime.code.core.ui.MiniMeIcon\n" not in text:
            anchor = "import com.minime.code.core.ui.MiniMeIcons\n"
            if anchor in text:
                text = text.replace(anchor, anchor + "import com.minime.code.core.ui.MiniMeIcon\n", 1)
            else:
                text = re.sub(r"(^package [\w.]+\n)", r"\1\nimport com.minime.code.core.ui.MiniMeIcon\n",
                              text, count=1, flags=re.M)

        if text != orig:
            open(path, "w", encoding="utf-8").write(text)
            changed_files += 1
            total_calls += n
            rel = os.path.relpath(path, ROOT)
            # 找出剩余的可能以 ImageVector 变量为参的 Icon 调用（人工检查）
            leftovers = re.findall(r"(?<![A-Za-z0-9_])Icon\(\s*([a-z]\w*)", text)
            leftovers = [l for l in leftovers if l not in ("painter", "bitmap")]
            if leftovers:
                manual_review.append((rel, sorted(set(leftovers))))

print(f"迁移文件数={changed_files}  迁移调用数={total_calls}")
for rel, lv in manual_review:
    print(f"  [人工检查] {rel}: Icon(变量) -> {lv}")
