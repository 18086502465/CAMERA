#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按版本清理 JAR 内 static/assets 的历史 chunk
============================================================
背景：recover_legacy_chunks.py 会把历史备份的 chunk 全部合并进当前 JAR，
      以便"部署前打开的旧页面"仍能加载旧 chunk（避免点击没反应）。
      但备份多了会让 JAR 无限膨胀（实测 9 版 → 61MB 涨到 67MB）。

本脚本依据 .versions 清单，只保留最近 N 个历史版本（默认 3），
"current" 版本永远保留。超出部分的文件从 JAR 中删除。

用法：sudo python3 prune_legacy_chunks.py [保留版数]
"""
import os
import re
import sys
import zipfile

CURRENT_JAR = "/opt/camera-detection-system/app/time-bucket-demo.jar"
ASSET_PREFIX = "BOOT-INF/classes/static/assets/"


def log(msg):
    print(msg, flush=True)


def main():
    keep = int(sys.argv[1]) if len(sys.argv) > 1 else 3

    with zipfile.ZipFile(CURRENT_JAR) as z:
        versions = {}
        names = z.namelist()
        if ASSET_PREFIX + ".versions" in names:
            for line in z.read(ASSET_PREFIX + ".versions").decode("utf-8").splitlines():
                if "=" in line:
                    fn, ver = line.split("=", 1)
                    versions[fn] = ver
        assets = {
            n[len(ASSET_PREFIX):]: z.read(n)
            for n in names
            if n.startswith(ASSET_PREFIX)
            and "/" not in n[len(ASSET_PREFIX):]
            and n.endswith((".js", ".css"))
        }

    if not versions:
        log("没有 .versions 清单，无法按版本清理")
        return 1

    # 历史版本号按「备份时间戳」排序 → 新的在前
    hist = sorted({v for v in versions.values() if v != "current"}, reverse=True)
    log("=== 当前历史版本 ===")
    for v in hist:
        cnt = sum(1 for f in versions if versions[f] == v)
        log("  %s : %d 个文件" % (v, cnt))

    if len(hist) <= keep:
        log("历史版本数 %d ≤ 保留上限 %d，无需清理" % (len(hist), keep))
        return 0

    drop = set(hist[keep:])
    log("")
    log("=== 将淘汰 %d 个旧版本 ===" % len(drop))
    for v in drop:
        log("  - %s" % v)

    remove_files = {f for f, ver in versions.items() if ver in drop}
    before = os.path.getsize(CURRENT_JAR)

    tmp = CURRENT_JAR + ".tmp"
    with zipfile.ZipFile(CURRENT_JAR) as zin, \
         zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        # 只写**非 assets** 的条目 —— assets 全部在下面统一重写，
        # 否则会出现重复条目（zipfile 会警告 Duplicate name，且读取时取最后一个）。
        for item in zin.infolist():
            name = item.filename
            if name.startswith(ASSET_PREFIX):
                continue          # assets 与 .versions 统一在下面写
            zout.writestr(item, zin.read(name))

        # 写回保留的文件与新清单
        new_versions = {f: v for f, v in versions.items() if f not in remove_files}
        for fname, ver in new_versions.items():
            if fname in assets:
                zout.writestr(ASSET_PREFIX + fname, assets[fname])
        blob = ("\n".join("%s=%s" % (k, new_versions[k]) for k in sorted(new_versions)) + "\n")
        zout.writestr(ASSET_PREFIX + ".versions", blob.encode("utf-8"))

    with zipfile.ZipFile(tmp) as z:
        manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
        if "JarLauncher" not in manifest or "Start-Class" not in manifest:
            log("  ! MANIFEST 异常，中止")
            os.remove(tmp)
            return 1
        log("  ✓ MANIFEST 正常")

    os.replace(tmp, CURRENT_JAR)
    os.chmod(CURRENT_JAR, 0o644)
    after = os.path.getsize(CURRENT_JAR)
    log("  ✓ 已更新，体积 %.1f MB → %.1f MB（减少 %.1f MB）"
        % (before / 1048576.0, after / 1048576.0, (before - after) / 1048576.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
