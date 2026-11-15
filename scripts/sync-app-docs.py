#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 docs-site 的 Markdown 源转换成 App 内置使用手册资源。

产物：app/src/main/assets/docs/
  <doc-id>.md   —— 剥离 VitePress frontmatter、容器语法转为引用块、站内链接改为 appdoc://
  manifest.json —— 目录结构（来源：docs-site/docs-manifest.json）

源：docs-site/docs/（与官网 VitePress 同源，保证「站上看到的」与「App 里读到的」一致）
特例：docs-site/app-home.md 作为 App 内阅读型首页，覆盖官网的 hero 布局首页。

用法：
  python3 scripts/sync-app-docs.py          # 生成产物
  python3 scripts/sync-app-docs.py --check  # 只校验产物与源是否一致（CI 用，不一致退出码 1）
"""
from __future__ import annotations

import json
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "docs-site", "docs")
HOME_SRC = os.path.join(ROOT, "docs-site", "app-home.md")
MANIFEST_SRC = os.path.join(ROOT, "docs-site", "docs-manifest.json")
DST = os.path.join(ROOT, "app", "src", "main", "assets", "docs")

# 旧品牌/旧架构残留：产物中不允许出现（首页致谢里的上游项目名 AiCode 是允许的）
FORBIDDEN = ("jieapi/minimecode", "minimecode.murk.top", "armsolo", "x86solo")

FENCE_RE = re.compile(r"^\s*(?:```|~~~)")
CONTAINER_OPEN_RE = re.compile(r"^:::+\s*(\w+)\s*(.*)$")
CONTAINER_CLOSE_RE = re.compile(r"^:::+\s*$")
LINK_RE = re.compile(r"\]\((/[^)]*)\)")

CN_LABELS = {"tip": "提示", "warning": "注意", "danger": "警告", "info": "说明", "details": "展开"}
EN_LABELS = {"tip": "Tip", "warning": "Warning", "danger": "Danger", "info": "Note", "details": "Details"}


def strip_frontmatter(text: str) -> str:
    """去掉 VitePress frontmatter（--- 包裹的元信息块）。"""
    if text.startswith("---"):
        end = text.find("\n---", 3)
        if end != -1:
            return text[end + 4:].lstrip("\n")
    return text


def convert_containers(lines: list[str], labels: dict[str, str]) -> list[str]:
    """VitePress 的 ::: tip/warning/danger 容器 -> Markdown 引用块。"""
    out: list[str] = []
    i = 0
    in_code = False
    while i < len(lines):
        line = lines[i]
        if FENCE_RE.match(line):
            in_code = not in_code
            out.append(line)
            i += 1
            continue
        if in_code:
            out.append(line)
            i += 1
            continue
        m = CONTAINER_OPEN_RE.match(line)
        if not m:
            out.append(line)
            i += 1
            continue
        kind = m.group(1).lower()
        head = m.group(2).strip()
        body: list[str] = []
        depth = 1
        i += 1
        while i < len(lines):
            cur = lines[i]
            if CONTAINER_CLOSE_RE.match(cur):
                depth -= 1
                if depth == 0:
                    i += 1
                    break
                body.append(cur)
                i += 1
                continue
            if CONTAINER_OPEN_RE.match(cur):
                depth += 1
            body.append(cur)
            i += 1
        label = labels.get(kind, kind.title())
        title = f"**【{label}】{head}**" if head else f"**【{label}】**"
        out.append(title)
        out.append("")
        for b in body:
            out.append("> " + b if b.strip() else ">")
        out.append("")
    return out


def rewrite_links(lines: list[str]) -> list[str]:
    """站内链接 /xxx -> appdoc://<doc-id>，代码块内保持原样；外链不动。"""

    def repl(m: re.Match[str]) -> str:
        raw = m.group(1)
        path, _, frag = raw.partition("#")
        if path in ("", "/"):
            doc_id = "index"
        elif path.rstrip("/") == "/en":
            doc_id = "en/index"
        else:
            doc_id = path.strip("/")
        tail = ("#" + frag) if frag else ""
        return f"](appdoc://{doc_id}{tail})"

    out: list[str] = []
    in_code = False
    for line in lines:
        if FENCE_RE.match(line):
            in_code = not in_code
            out.append(line)
            continue
        if in_code:
            out.append(line)
            continue
        out.append(LINK_RE.sub(repl, line))
    return out


def render(text: str, doc_id: str) -> str:
    """Markdown 源 -> App 内置文档正文。"""
    labels = EN_LABELS if doc_id.startswith("en/") else CN_LABELS
    body = strip_frontmatter(text)
    lines = convert_containers(body.split("\n"), labels)
    lines = rewrite_links(lines)
    return "\n".join(lines).strip() + "\n"


def load_manifest() -> dict:
    with open(MANIFEST_SRC, encoding="utf-8") as f:
        data = json.load(f)
    data.pop("_comment", None)
    return data


def generate(out_dir: str) -> list[str]:
    """生成全部产物到 out_dir，返回生成的文档 id 列表。"""
    manifest = load_manifest()
    ids: list[str] = []
    for group in manifest["groups"]:
        for item in group["items"]:
            ids.append(item["id"])

    if os.path.exists(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir, exist_ok=True)

    for doc_id in ids:
        if doc_id == "index":
            src = HOME_SRC
        else:
            src = os.path.join(SRC, doc_id + ".md")
        if not os.path.exists(src):
            raise SystemExit(f"缺少文档源：{src}")
        with open(src, encoding="utf-8") as f:
            content = render(f.read(), doc_id)
        target = os.path.join(out_dir, doc_id + ".md")
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "w", encoding="utf-8") as f:
            f.write(content)

    with open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")

    return ids


def check_leftover(out_dir: str) -> list[str]:
    """检查产物里是否残留旧品牌/旧架构说明。"""
    bad: list[str] = []
    for root, _, files in os.walk(out_dir):
        for name in files:
            if not name.endswith(".md"):
                continue
            path = os.path.join(root, name)
            with open(path, encoding="utf-8") as f:
                text = f.read()
            for token in FORBIDDEN:
                if token in text:
                    bad.append(f"{os.path.relpath(path, out_dir)}: {token}")
    return bad


def check_links(out_dir: str) -> list[str]:
    """检查 appdoc:// 内部链接指向的文档是否存在。"""
    broken: list[str] = []
    for root, _, files in os.walk(out_dir):
        for name in files:
            if not name.endswith(".md"):
                continue
            path = os.path.join(root, name)
            with open(path, encoding="utf-8") as f:
                text = f.read()
            for ref in re.findall(r"appdoc://([^\s)]+)", text):
                target = ref.split("#")[0]
                if target and not os.path.exists(os.path.join(out_dir, target + ".md")):
                    broken.append(f"{os.path.relpath(path, out_dir)} -> {target}")
    return broken


def diff_dirs(expected: str, actual: str) -> list[str]:
    """比较两个目录的内容差异。"""
    problems: list[str] = []

    def rel_files(root: str) -> dict[str, str]:
        out = {}
        for base, _, files in os.walk(root):
            for name in files:
                full = os.path.join(base, name)
                with open(full, "rb") as f:
                    out[os.path.relpath(full, root)] = f.read().decode("utf-8", "replace")
        return out

    exp = rel_files(expected)
    act = rel_files(actual)
    for key in sorted(set(exp) | set(act)):
        if key not in act:
            problems.append(f"缺少产物：{key}")
        elif key not in exp:
            problems.append(f"多余产物：{key}")
        elif exp[key] != act[key]:
            problems.append(f"内容不一致：{key}")
    return problems


def main() -> int:
    check_only = "--check" in sys.argv

    if check_only:
        with tempfile.TemporaryDirectory() as tmp:
            generate(tmp)
            problems = diff_dirs(tmp, DST) if os.path.isdir(DST) else ["产物目录不存在：" + DST]
            problems += ["残留旧品牌：" + x for x in check_leftover(tmp)]
            problems += ["失效内链：" + x for x in check_links(tmp)]
            if problems:
                print("内置文档与文档源不一致，请运行：python3 scripts/sync-app-docs.py")
                for p in problems:
                    print("  -", p)
                return 1
        print("内置文档与文档源一致 ✓")
        return 0

    ids = generate(DST)
    leftover = check_leftover(DST)
    broken = check_links(DST)
    print(f"生成文档数：{len(ids)}")
    print(f"产物目录：{os.path.relpath(DST, ROOT)}")
    if leftover:
        print("❌ 残留旧品牌/旧架构：")
        for x in leftover:
            print("  -", x)
        return 1
    if broken:
        print("❌ 失效的文档内链：")
        for x in broken:
            print("  -", x)
        return 1
    print("✅ 无旧品牌残留，内部链接全部有效")
    return 0


if __name__ == "__main__":
    sys.exit(main())
