#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""两份 application.yml 的 key 结构对账（只比 key 路径，不比 value）。

为什么需要这个守卫
------------------
仓库里有两份 application.yml：

  fact-product/src/main/resources/application.yml   JAR 内置默认（开发/本机/兜底）
  installer/templates/application.yml              发布包 config/，服务器外部配置

Spring 外部配置是**按 key 合并**（不是整文件替换）。所以两份必须认识同一套 key：

  - 只在模板里加、忘了加进 JAR → 模板里那一项是本机默认值兜底，行为"看似对但不生效"
  - 只在 JAR 里加、忘了加进模板 → 客户在服务器配置里根本看不到这一项，无从修改
  - 两边都加错一个字母 → 只有一边生效，且两边都不报错

2026-11-12 加 License 的 7 个 key 时就是靠手工双写维护的，没有任何机制防止下次漏一边。

为什么**不能**用 diff 判等
--------------------------
两份文件的 value 本来就有意不同（实测）：
  数据源 URL      localhost            vs 127.0.0.1
  连接池          10/10/200            vs 5/5/100（现场 2 台相机，不需要 200）
  validation      SELECT 1 FROM DUAL   vs SELECT 1
  图片目录        ${app.home}/data/... vs /main/app/data/images/...
  logging.level   缺省                 vs root/com.wms 显式 INFO
判等会立刻误报，且会诱导别人把现场参数改回本机值。

本脚本只比 **key 路径集合**，差异只允许出现在 KV_ALLOW 里登记过的键。

用法
----
  python3 installer/linux/scripts/check_config_parity.py          # 校验，失败 exit 1
  python3 installer/linux/scripts/check_config_parity.py --list    # 打印两侧 key 差异

仅标准库（现场不一定有 PyYAML）。
"""
import io
import os
import sys

if hasattr(sys.stdout, "buffer"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(  # installer/linux/scripts → 仓库根
    os.path.abspath(__file__)))))
JAR_YML = os.path.join(REPO, "fact-product", "src", "main", "resources", "application.yml")
TPL_YML = os.path.join(REPO, "installer", "templates", "application.yml")

# 允许「只在一侧存在」的 key（两侧都不报）。每一条都要写理由，否则下一个人不知道能不能删。
# 只登记**叶子 key**；父节点（如 app.security）由脚本自动排除，不用登记。
KV_ALLOW = {
    # 本机开发用的连接池微调：现场不需要 prepared statement 缓存与 stat/wall 过滤器
    "spring.datasource.druid.pool-prepared-statements": "仅本机开发启用",
    "spring.datasource.druid.max-pool-prepared-statement-per-connection-size": "仅本机开发启用",
    "spring.datasource.druid.filters": "仅本机开发启用 stat,wall 监控",
    # 本机不需要反向代理信任配置
    "app.security.trusted-proxies": "仅本机开发有此项",
    # 模板侧显式写死清理 cron；JAR 侧用 DataCleanupTask 的 @Scheduled 常量
    "app.cleanup.cron": "仅发布模板显式声明",
    # 模板侧显式打开 root/com.wms 的 INFO，便于现场排障
    "logging.level.root": "仅发布模板显式声明",
    "logging.level.com.wms": "仅发布模板显式声明",
}


def flatten_keys(path):
    """把 YAML 压平成**叶子** key 路径集合（缩进栈实现，够用于 application.yml）。

    只返回带值的叶子 key —— 父节点（spring.datasource）只是结构，不是配置项，
    参与比对会制造"只在一侧"的假阳性。

    不处理：多行字符串、锚点、流式 map、list 内的 map —— 本项目配置里都没有。
    遇到 list 项（`- xxx`）直接跳过，因为配置里 list 只出现在叶子值层，不构成新 key。
    """
    all_keys = set()
    parents = set()
    stack = []  # [(indent, key)]
    with open(path, encoding="utf-8-sig") as f:
        for raw in f:
            line = raw.rstrip("\n").rstrip("\r")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            stripped = line.lstrip()
            if stripped.startswith("- ") or stripped == "-":
                continue  # list 项，不构成 key
            if ":" not in stripped:
                continue
            indent = len(line) - len(stripped)
            name, _, value = stripped.partition(":")
            name = name.strip()
            if not name or " " in name:
                continue
            while stack and stack[-1][0] >= indent:
                stack.pop()
            path_parts = [k for _, k in stack] + [name]
            full = ".".join(path_parts)
            all_keys.add(full)
            if not value.strip():  # 只有 key、无 value → 它是父节点
                parents.add(full)
                stack.append((indent, name))
    return all_keys - parents


def main():
    for p in (JAR_YML, TPL_YML):
        if not os.path.isfile(p):
            print("错误：找不到 %s" % p)
            return 1

    jar_keys = flatten_keys(JAR_YML)
    tpl_keys = flatten_keys(TPL_YML)

    only_jar = sorted(jar_keys - tpl_keys)
    only_tpl = sorted(tpl_keys - jar_keys)

    if "--list" in sys.argv:
        print("JAR 内置 key：%d 个" % len(jar_keys))
        print("模板 key：    %d 个" % len(tpl_keys))
        print("\n只在 JAR 里：")
        for k in only_jar:
            print("  -", k, "  # 允许" if k in KV_ALLOW else "  ← 未登记")
        print("\n只在模板里：")
        for k in only_tpl:
            print("  -", k, "  # 允许" if k in KV_ALLOW else "  ← 未登记")
        print("\n共同 key：%d 个" % len(jar_keys & tpl_keys))
        return 0

    bad = [(k, "only-jar") for k in only_jar if k not in KV_ALLOW]
    bad += [(k, "only-template") for k in only_tpl if k not in KV_ALLOW]

    if bad:
        print("错误：两份 application.yml 的 key 结构不一致（值不同是允许的，key 缺一个才算错）")
        for k, where in bad:
            print("  %-20s %s" % (where, k))
        print("")
        print("修法：把该 key 补进另一份；若确属单边专用，登记到本脚本的 KV_ALLOW 并写理由。")
        print("  JAR   → %s" % os.path.relpath(JAR_YML, REPO))
        print("  模板  → %s" % os.path.relpath(TPL_YML, REPO))
        return 1

    print("✓ 配置 key 结构一致（共同 %d 个 key，登记为单边专用的 %d 个）"
          % (len(jar_keys & tpl_keys),
             len((jar_keys ^ tpl_keys) & set(KV_ALLOW))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
