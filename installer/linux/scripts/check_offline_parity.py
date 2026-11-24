#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线依赖清单 vs 在线依赖清单对账（防止离线包装了却在线装不上）。

为什么需要这个守卫
------------------
新机部署有两条依赖安装路径，包清单写在两个文件里：

  在线路径：deploy.sh stage_p1 的 pkgs=(...)           → apt-get install
  离线路径：fetch-offline-deps.sh 的 DEP_PKGS=(...)    → apt --download-only 预下载

交付文档明确要求「两处必须同改」，但此前只靠人记住，没有任何机器校验：
改一处漏另一处 → 离线包打进来的 deb 与在线装的包不一致，现场表现是
「离线机能装、在线机缺包」或「离线包少一个 deb，dpkg 阶段才炸」。
2026-11-24 新增 rsync/zstd/apache2-utils 时就是手工双写，属于典型易错点。

归一规则
--------
两边都是「基础 apt 包」集合，应当完全相等。差异项按语义处理：

  - nginx：可选，deploy.sh 用 --with-nginx 条件追加（pkgs+=(nginx)），不强制进离线包。
  - openjdk-8-jdk：两边都必须覆盖 JDK8，但获取方式不同 —— 在线由 install_jdk8()
    处理（jammy 官方源 / 24.04 走 Adoptium），离线由 deb 直接提供。因此
    fetch-offline-deps.sh 必须列它，deploy.sh 的 pkgs 不应列它（会与 install_jdk8 重复装）。
    本脚本把两边的 JDK8 视为同一「能力项」，不做字符串相等要求。

用法
----
  python3 installer/linux/scripts/check_offline_parity.py          # 校验，失败 exit 1
  python3 installer/linux/scripts/check_offline_parity.py --list    # 打印两侧解析结果

仅标准库。
"""
import io
import re
import sys

if hasattr(sys.stdout, "buffer"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import os

# __file__ = <root>/installer/linux/scripts/check_offline_parity.py
# 向上 4 层到仓库根：<root>
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

DEPLOY_SH = os.path.join(ROOT, "installer", "linux", "scripts", "deploy.sh")
FETCH_SH = os.path.join(ROOT, "installer", "linux", "fetch-offline-deps.sh")

# 在线路径特有的「条件追加」与「单独处理」项，不参与集合相等判定
IGNORE = {"nginx"}
# JDK8 两边获取方式不同，归一为同一能力项后单独核对
JDK8 = "openjdk-8-jdk"


def parse_array(path, var_name):
    """解析 bash 数组 VAR=(a b c) 或换行写法，返回包名列表。"""
    with open(path, encoding="utf-8", errors="replace") as f:
        text = f.read()
    # 支持 VAR=( ...) 跨行；捕获到第一个 ) 为止（数组内无嵌套括号）
    m = re.search(r"\b" + re.escape(var_name) + r"=\(([^)]*)\)", text, re.S)
    if not m:
        return None
    body = m.group(1)
    # 去注释、去变量展开（$xxx / ${xxx}）
    body = re.sub(r"#.*", "", body)
    body = re.sub(r"\$[\w{}]+", "", body)
    return [t for t in re.split(r"\s+", body.strip()) if t]


def main():
    list_only = "--list" in sys.argv[1:]

    online = parse_array(DEPLOY_SH, "pkgs")
    offline = parse_array(FETCH_SH, "DEP_PKGS")

    problems = []
    if online is None:
        problems.append("deploy.sh 未找到 pkgs=(...) 依赖数组")
    if offline is None:
        problems.append("fetch-offline-deps.sh 未找到 DEP_PKGS=(...) 依赖数组")
    if problems:
        for p in problems:
            print("❌ " + p)
        return 1

    online_set = set(online) - IGNORE
    offline_set = set(offline) - IGNORE

    # JDK8 单独核：离线必须显式下载；在线由 install_jdk8 处理，不在 pkgs 里属正常。
    # 若在线 pkgs 里出现 JDK8 也不算错（等价能力），故只校验离线侧必须覆盖。
    if JDK8 not in offline_set:
        problems.append("fetch-offline-deps.sh 的 DEP_PKGS 缺 openjdk-8-jdk —— 离线机将没有 JDK8")
    with open(DEPLOY_SH, encoding="utf-8", errors="replace") as f:
        deploy_text = f.read()
    online_cap = (online_set - {JDK8}) | ({JDK8} if "install_jdk8" in deploy_text else set())
    offline_cap = offline_set  # 含 JDK8

    if online_cap != offline_cap:
        only_online = sorted(online_cap - offline_cap)
        only_offline = sorted(offline_cap - online_cap)
        if only_online:
            problems.append("仅在线路径安装、离线包缺失：%s（离线机部署会缺包）" % ", ".join(only_online))
        if only_offline:
            problems.append("仅离线包下载、在线路径不装：%s（两路径行为不一致）" % ", ".join(only_offline))

    print("在线 pkgs     : %s" % ", ".join(sorted(online)))
    print("离线 DEP_PKGS : %s" % ", ".join(sorted(offline)))
    print("归一能力集    : %s" % ", ".join(sorted(online_cap)))

    if problems:
        print()
        for p in problems:
            print("❌ " + p)
        print("\n请同步 deploy.sh stage_p1 的 pkgs 与 fetch-offline-deps.sh 的 DEP_PKGS。")
        return 1

    if not list_only:
        print("\n✅ 两条依赖安装路径的包清单一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
