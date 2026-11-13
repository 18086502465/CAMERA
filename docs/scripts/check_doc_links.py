#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""docs 文档一致性校验。

检查四件事：
1. 所有 Markdown 里的相对链接目标是否存在（`](path)`）。
2. 文件内锚点 `](#anchor)` 是否对应真实标题。
3. `docs/` 根目录只允许「现行规范」，历史稿必须在 `docs/archive/` 下。
4. 源目录（fact-product/、frontend/、installer/）内不得出现 .md 文档。

用法：
    python docs/scripts/check_doc_links.py            # 校验，失败退出码 1
    python docs/scripts/check_doc_links.py --quiet    # 只输出问题
"""

import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# docs/ 根目录允许出现的 Markdown（现行规范，与代码同步维护）
# 新增规范文档时同步加一行，并更新 docs/README.md 的目录树与表格
DOCS_ROOT_ALLOWED = {
    "README.md",
    "架构说明.md",
    "模块设计.md",
    "接口文档.md",
    "部署与运维手册.md",
    "配置项清单.md",
    "服务器文件布局.md",
    "使用说明.md",
    "服务器授权设计.md",
    "踩坑速查.md",
}

# 不参与检查的目录
SKIP_DIRS = {
    ".git", ".workbuddy", "node_modules", "dist-release", "dist", "target",
    ".idea", ".vscode", "__pycache__",
}

# 源目录内不允许放文档
SOURCE_DIRS = ("fact-product", "frontend", "installer")

LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
FENCE_RE = re.compile(r"^(```|~~~)")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.*)$", re.M)
ANCHOR_STRIP_RE = re.compile(r"[、。，：；！？（）()\[\]`*_\"'’“”·—…]")


def slugify(title):
    """GitHub / VSCode 预览口径的中文标题锚点。"""
    text = ANCHOR_STRIP_RE.sub("", title.strip().lower())
    return re.sub(r"\s+", "-", text)


def collect_anchors(text):
    return {slugify(m.group(2)) for m in HEADING_RE.finditer(strip_code_fences(text))}


def walk_markdown():
    for root, dirs, files in os.walk(REPO):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            if name.endswith(".md"):
                yield os.path.join(root, name)


def strip_code_fences(text):
    """去掉围栏代码块，避免把代码里的 []() 当链接。"""
    out, in_fence = [], False
    for line in text.splitlines():
        if FENCE_RE.match(line.strip()):
            in_fence = not in_fence
            out.append("")
            continue
        out.append("" if in_fence else line)
    return "\n".join(out)


def check_links(path, problems, checked):
    with open(path, encoding="utf-8", errors="replace") as fh:
        raw = fh.read()
    text = strip_code_fences(raw)

    base = os.path.dirname(path)
    rel_src = os.path.relpath(path, REPO).replace("\\", "/")
    own_anchors = None  # 惰性计算

    for lineno, line in enumerate(text.splitlines(), 1):
        for target in LINK_RE.findall(line):
            target = target.strip()
            if not target or target.startswith(("http://", "https://", "mailto:", "tel:")):
                continue
            if target.startswith("/"):        # 站内绝对路径，不校验
                continue

            clean, _, anchor = target.partition("#")

            if not clean:                     # 纯锚点：#anchor
                if own_anchors is None:
                    own_anchors = collect_anchors(raw)
                if anchor and anchor not in own_anchors:
                    problems.append("%s:%d  失效锚点 -> %s" % (rel_src, lineno, target))
                continue

            clean = clean.split("?", 1)[0]
            full = os.path.normpath(os.path.join(base, clean))
            if not os.path.exists(full):
                problems.append("%s:%d  失效链接 -> %s" % (rel_src, lineno, target))
                continue

            if anchor and full.endswith(".md"):
                with open(full, encoding="utf-8", errors="replace") as fh:
                    if anchor not in collect_anchors(fh.read()):
                        problems.append(
                            "%s:%d  失效锚点 -> %s" % (rel_src, lineno, target)
                        )
                        continue
            checked[0] += 1


def check_structure(problems):
    docs_dir = os.path.join(REPO, "docs")
    if not os.path.isdir(docs_dir):
        problems.append("docs/ 目录不存在")
        return

    for name in sorted(os.listdir(docs_dir)):
        full = os.path.join(docs_dir, name)
        if os.path.isfile(full) and name.endswith(".md") and name not in DOCS_ROOT_ALLOWED:
            problems.append(
                "docs/%s  放错位置：docs/ 根只放现行规范 %d 份，历史稿移到 docs/archive/"
                % (name, len(DOCS_ROOT_ALLOWED) - 1)
            )

    for src in SOURCE_DIRS:
        src_dir = os.path.join(REPO, src)
        if not os.path.isdir(src_dir):
            continue
        for root, dirs, files in os.walk(src_dir):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
            for name in files:
                if name.endswith(".md"):
                    rel = os.path.relpath(os.path.join(root, name), REPO).replace("\\", "/")
                    problems.append("%s  源目录内不应放文档，移到 docs/archive/" % rel)


def main():
    quiet = "--quiet" in sys.argv
    problems, checked = [], [0]

    for path in walk_markdown():
        check_links(path, problems, checked)
    check_structure(problems)

    md_count = sum(1 for _ in walk_markdown())

    if problems:
        print("文档校验失败（%d 项）：" % len(problems))
        for p in problems:
            print("  " + p)
        return 1

    if not quiet:
        print("文档校验通过：%d 个 Markdown，%d 条相对链接，结构合规。" % (md_count, checked[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
