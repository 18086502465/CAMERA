#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""旧布局路径守卫（2026-11 统一布局改造）

背景
----
2026-11 起所有产品文件收敛到 /main/app 一个根：
    /opt/camera-detection-system      → /main/app/dist
    /var/lib/camera-detection-system  → /main/app
    /data/camera-detection            → /main/app/data
    /var/lib/mysql                    → /main/app/data/mysql

改造要动 15 个脚本里的 179 处绝对路径。**漏改一处就是线上故障**：
例如脚本把图片写到 /data/camera-detection/... 而应用从 /main/app/data/... 读，
表现为「上传成功但看不到图」；或者 cron 还调 /usr/local/bin/xxx.sh 而新布局
不再往那里装脚本，清理任务从此静默不执行。

这个脚本把「旧路径回流」变成 CI 可拦截的错误：
  - 扫描 installer/**（全部脚本、unit、模板）
  - 扫描 frontend/src/**
  - 扫描 docs/scripts/**（运维与回归脚本；源目录以外的脚本同样会写错盘）
  - 命中旧路径字面量即失败

白名单
------
  - installer/linux/scripts/migrate-to-main-app.sh：迁移脚本的职责就是操作旧路径
  - docs/**：文档里必须能写「旧 → 新」对照表，不属于代码
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 旧布局字面量（顺序无关，命中任一即报）
LEGACY_PATTERNS = [
    r"/opt/camera-detection-system",
    r"/var/lib/camera-detection-system",
    r"/data/camera-detection",
    r"/usr/local/bin/cleanup-defect-images\.sh",
    r"/usr/local/bin/cleanup-zk54-incremental\.sh",
    r"/usr/local/bin/check-disk-usage\.sh",
]

SCAN_ROOTS = ["installer", "frontend/src", "docs/scripts"]
SCAN_EXT = {".sh", ".service", ".timer", ".yml", ".yaml", ".js", ".vue", ".ts"}

WHITELIST = {
    "installer/linux/scripts/migrate-to-main-app.sh",
    "docs/scripts/check_legacy_paths.py",   # 守卫自身含匹配模式
}

# 允许出现在「注释里的历史说明」不算白名单 —— 统一处理更简单：
# 只要文件在扫描范围内且不是白名单，出现旧路径就是错。注释也想改成新路径。


def tracked_files():
    """优先用 git 列表（避免扫到 node_modules / target）；非 git 环境退回 walk。

    必须带 --others --exclude-standard：只用 `git ls-files` 只列已跟踪文件，
    新写的脚本还没 git add 就扫不到 —— 本地跑出「✅ 全绿」而 CI 才红，
    甚至提交前根本发现不了。--others 把未跟踪但未被 .gitignore 排除的文件
    也纳进来，本地与 CI 行为一致。
    """
    try:
        out = subprocess.check_output(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
            cwd=ROOT, stderr=subprocess.DEVNULL,
        ).decode("utf-8", "replace")
        files = [line.strip() for line in out.splitlines() if line.strip()]
        if files:
            return files
    except Exception:
        pass
    files = []
    for base, dirs, names in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in {".git", "node_modules", "target", "dist"}]
        for n in names:
            files.append(os.path.relpath(os.path.join(base, n), ROOT).replace("\\", "/"))
    return files


def main():
    pats = [re.compile(p) for p in LEGACY_PATTERNS]
    hits = []
    checked = 0

    for rel in tracked_files():
        if rel in WHITELIST:
            continue
        if not any(rel == r or rel.startswith(r + "/") for r in SCAN_ROOTS):
            continue
        if os.path.splitext(rel)[1].lower() not in SCAN_EXT:
            continue
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            continue
        checked += 1
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                for lineno, line in enumerate(fh, 1):
                    for pat in pats:
                        if pat.search(line):
                            hits.append((rel, lineno, line.strip()[:160]))
                            break
        except OSError:
            continue

    if hits:
        print("❌ 发现旧布局路径（统一布局为 /main/app）：")
        for rel, lineno, snippet in hits:
            print("   %s:%d  %s" % (rel, lineno, snippet))
        print()
        print("   新布局对照：")
        print("     /opt/camera-detection-system      → /main/app/dist")
        print("     /var/lib/camera-detection-system  → /main/app")
        print("     /data/camera-detection            → /main/app/data")
        print("     /usr/local/bin/<script>.sh        → /main/app/dist/scripts/<script>.sh")
        print("   如确属迁移脚本，请加入 WHITELIST。")
        return 1

    print("✅ 未发现旧布局路径（扫描 %d 个文件）" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
