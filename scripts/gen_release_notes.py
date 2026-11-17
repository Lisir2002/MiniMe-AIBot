#!/usr/bin/env python3
"""生成完整、可读的 GitHub Release 说明日志。

背景：CI 原先用 `git log --oneline` 只取 commit 标题行，正文里的根因/改动清单全部丢失，
Release 页面只剩一串平铺短句，无法追溯。本脚本改为：

- 读取 `prev_tag..HEAD` 的每个 commit 的**完整标题 + 正文**；
- 按 conventional commits 前缀（feat/fix/refactor/...）分类分组；
- 每个 commit 保留 subject 与逐行正文，输出结构化 Markdown。

用法：
    python3 scripts/gen_release_notes.py <current_tag> [prev_tag]
    # 例：python3 scripts/gen_release_notes.py v1.0.0-rc12 v1.0.0-rc11

不带 prev_tag 时自动推断：正式版对比上一个正式版，预发布版对比最近的一个 tag。
输出写到 stdout，CI 里重定向到 $GITHUB_OUTPUT 的 body（用 heredoc 分隔）。
"""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_SLUG = "Lisir2002/MiniMe-AIBot"

# conventional commit 类型 → 分组标题（按此顺序输出）
GROUPS = [
    ("新功能", ["feat", "feature"]),
    ("修复与优化", ["fix", "perf", "hotfix"]),
    ("重构", ["refactor"]),
    ("文档", ["docs"]),
    ("构建与测试", ["build", "ci", "chore", "test", "deps"]),
    ("其他变更", []),  # 兜底
]

# 用于 git log 的字段/记录分隔符（ASCII US/RS，避免与正文冲突）
FIELD_SEP = "\x1f"
REC_SEP = "\x1e"


def run_git(*args):
    return subprocess.run(
        ["git", "-C", ROOT, *args],
        capture_output=True, text=True, check=True,
    ).stdout


def tag_exists(tag):
    if not tag:
        return False
    r = subprocess.run(
        ["git", "-C", ROOT, "rev-parse", "-q", "--verify", f"refs/tags/{tag}"],
        capture_output=True,
    )
    return r.returncode == 0


def infer_prev_tag(cur_tag):
    tags = run_git("tag", "--sort=-creatordate").splitlines()
    tags = [t for t in tags if t != cur_tag]
    if re.fullmatch(r"v\d+\.\d+\.\d+", cur_tag):
        # 正式版：对比上一个正式版，汇总整个 rc 周期
        stable = [t for t in tags if re.fullmatch(r"v\d+\.\d+\.\d+", t)]
        return stable[0] if stable else (tags[0] if tags else None)
    # 预发布版：对比最近的一个 tag
    return tags[0] if tags else None


def load_commits(cur_tag, prev_tag):
    """返回 commit 列表，每项 (sha, subject, body_lines)。"""
    rev_range = f"{prev_tag}..{cur_tag}" if prev_tag else cur_tag
    fmt = f"%H{FIELD_SEP}%s{FIELD_SEP}%b{REC_SEP}"
    raw = run_git("log", "--no-decorate", f"--format={fmt}", rev_range)
    commits = []
    for rec in raw.split(REC_SEP):
        rec = rec.strip("\n")
        if not rec.strip():
            continue
        parts = rec.split(FIELD_SEP, 2)
        if len(parts) < 2:
            continue
        sha, subject = parts[0].strip(), parts[1].strip()
        body = parts[2] if len(parts) == 3 else ""
        body_lines = [ln.rstrip() for ln in body.splitlines() if ln.strip()]
        commits.append((sha, subject, body_lines))
    return commits


def classify(subject):
    """返回 (组名, 类型前缀, 作用域, 纯描述)。"""
    m = re.match(r"^([a-zA-Z]+)(?:\(([^)]+)\))?\s*:\s*(.+)$", subject)
    if not m:
        return "其他变更", "", "", subject
    typ, scope, desc = m.group(1).lower(), m.group(2) or "", m.group(3)
    for name, types in GROUPS:
        if typ in types:
            return name, typ, scope, desc
    return "其他变更", typ, scope, desc


def render(cur_tag, prev_tag, commits):
    out = []
    out.append(f"## MiniMe {cur_tag}")
    out.append("")
    if commits:
        out.append(f"本次更新包含 **{len(commits)}** 项变更。")
    else:
        out.append("与上一版本同一提交，无新增变更。")
    out.append("")
    out.append(f"> 真机 arm64 包：`minimecode-arm64-{cur_tag}.apk` · ")
    out.append(f"> 云端服务：`agent-server-{cur_tag}.jar`（供「服务器与云端」一键部署/升级）")
    out.append("")

    grouped = {name: [] for name, _ in GROUPS}
    for sha, subject, body_lines in commits:
        gname, typ, scope, desc = classify(subject)
        grouped[gname].append((sha, typ, scope, desc, body_lines))

    for gname, _ in GROUPS:
        items = grouped[gname]
        if not items:
            continue
        out.append(f"### {gname}")
        out.append("")
        for sha, typ, scope, desc, body_lines in items:
            short = sha[:8]
            label = f"**{scope}**" if scope else (f"**{typ}**" if typ else "")
            head = f"- {label + '：' if label else ''}{desc} (`{short}`)"
            out.append(head)
            for ln in body_lines:
                text = ln.strip()
                # 正文里本就是列表项（"- xxx"）的，去掉原 "-" 再用统一缩进，避免出现 "- - xxx"。
                if text.startswith("- "):
                    text = text[2:].strip()
                out.append(f"    - {text}")
        out.append("")

    if prev_tag:
        out.append(
            f"**Full Changelog**: "
            f"https://github.com/{REPO_SLUG}/compare/{prev_tag}...{cur_tag}"
        )
    else:
        out.append(f"**Commits**: https://github.com/{REPO_SLUG}/commits/{cur_tag}")
    out.append("")
    return "\n".join(out)


def main():
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    cur_tag = sys.argv[1]
    prev_tag = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] else infer_prev_tag(cur_tag)
    if prev_tag and not tag_exists(prev_tag):
        print(f"warn: prev tag {prev_tag} not found, using all history", file=sys.stderr)
        prev_tag = None
    commits = load_commits(cur_tag, prev_tag)
    sys.stdout.write(render(cur_tag, prev_tag, commits))


if __name__ == "__main__":
    main()
