#!/usr/bin/env python3
"""第二轮迁移：Icon(变量) 且变量声明为 ImageVector -> MiniMeIcon(变量)。

规则：
- 匹配 (?<![A-Za-z0-9_])Icon\\(\\s*(\\w+)\\s*[,)] ，变量在当前文件声明为
  `\\w+: ImageVector`（含参数与 val）才替换。
- Icon( if (... ) MiniMeIcons... 也替换（条件表达式返回 ImageVector）。
- painterResource / bitmap 等不动。
"""
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "app/src/main/java"))

changed = 0
total = 0
skip_report = []

for dirpath, _dirs, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        text = open(path, encoding="utf-8").read()
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        pkg = pkg.group(1) if pkg else ""
        same_pkg = pkg == "com.minime.code.core.ui"

        iv_vars = set(re.findall(r"(\w+)\s*:\s*ImageVector\b", text))
        if not iv_vars:
            continue

        orig = text
        n = 0

        def repl(m):
            global n
            var = m.group(1)
            if var in iv_vars:
                n += 1
                return "MiniMeIcon(" + var
            return m.group(0)

        text = re.sub(r"(?<![A-Za-z0-9_])Icon\(\s*(\w+)\s*[,)]", repl, text)
        # Icon( if (...) -> MiniMeIcon( if (...)  （条件表达式结果为 ImageVector）
        text, n_if = re.subn(r"(?<![A-Za-z0-9_])Icon\(\s*if\s*\(", "MiniMeIcon(if (", text)
        n += n_if

        if n == 0 or text == orig:
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
        total += n
        print(f"  {os.path.relpath(path, ROOT)}: +{n}")

print(f"第二轮迁移文件数={changed} 调用数={total}")
