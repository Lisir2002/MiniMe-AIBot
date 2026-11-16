#!/usr/bin/env python3
"""第三轮兜底迁移：多行参数形态。

- Icon(\\s*imageVector\\s*=        -> MiniMeIcon(imageVector =   （任意文件）
- Icon(\\s*if\\s*\\(               -> MiniMeIcon(if (            （条件 ImageVector）
- Icon(\\s*<var>[,)] （var 声明为 ImageVector）
"""
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "app/src/main/java"))

changed = 0
total = 0

for dirpath, _dirs, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        text = open(path, encoding="utf-8").read()
        if path.endswith("MiniMeIcon.kt"):
            continue

        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        same_pkg = bool(pkg) and pkg.group(1) == "com.minime.code.core.ui"
        iv_vars = set(re.findall(r"(\w+)\s*:\s*ImageVector\b", text))

        orig = text
        n_count = 0

        def repl_var(m):
            nonlocal_count = 0
            if m.group(1) in iv_vars:
                return "MiniMeIcon(" + m.group(1)
            return m.group(0)

        text, a = re.subn(r"(?<![A-Za-z0-9_])Icon\(\s*imageVector\s*=", "MiniMeIcon(imageVector =", text)
        text, b = re.subn(r"(?<![A-Za-z0-9_])Icon\(\s*if\s*\(", "MiniMeIcon(if (", text)
        # 变量形态（需计数）
        var_matches = re.findall(r"(?<![A-Za-z0-9_])Icon\(\s*(\w+)\s*[,)]", text)
        var_hits = [v for v in var_matches if v in iv_vars]
        for v in var_hits:
            text = re.sub(r"(?<![A-Za-z0-9_])Icon\(\s*" + re.escape(v) + r"\s*[,)]",
                          "MiniMeIcon(" + v, text, count=1)
        n_count = a + b + len(var_hits)

        if n_count == 0 or text == orig:
            continue

        if "import com.minime.code.core.ui.MiniMeIcon\n" not in text and not same_pkg:
            anchor = "import com.minime.code.core.ui.MiniMeIcons\n"
            if anchor in text:
                text = text.replace(anchor, anchor + "import com.minime.code.core.ui.MiniMeIcon\n", 1)
            else:
                text = re.sub(r"(^package [\w.]+\n)", r"\1\nimport com.minime.code.core.ui.MiniMeIcon\n",
                              text, count=1, flags=re.M)

        open(path, "w", encoding="utf-8").write(text)
        changed += 1
        total += n_count
        print(f"  {os.path.relpath(path, ROOT)}: +{n_count}")

print(f"第三轮迁移文件数={changed} 调用数={total}")
