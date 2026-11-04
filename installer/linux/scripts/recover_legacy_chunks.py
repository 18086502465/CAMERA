#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从服务器上的历史 JAR 备份中找回旧版前端 chunk
============================================================
背景（2026-11-04 现场故障复现）：
    Vite 产物的 chunk 文件名带内容哈希，每次构建全部改名。
    用户浏览器里开着部署前打开的旧页面 → 它的路由懒加载仍指向旧 chunk
    （如 ImagesView-3Im0_kdo.js），而新部署已把这些文件替换掉 → 404 →
        "Failed to fetch dynamically imported module" →
    vue-router 导航 Promise 被 reject → **点击完全没反应**
    （URL 不变、页面不刷新、也没有任何后端请求）。

做法：
    遍历 app/*.bak-* JAR，把其中的 static/assets/*.js|css 平铺合并进当前 JAR。
    文件名带哈希 → 与新版本不会冲突 → 旧页面仍能命中旧 chunk。
    同时写 .versions 清单记录每个文件来自哪一版，便于按版清理。

实现要点：
    ★ 用 Python zipfile **增量写入**，不用 jar cf 重打包 ——
      重打包会破坏 Spring Boot 的 MANIFEST（Main-Class: JarLauncher /
      Start-Class: com.wms.TimeBucketApplication）与 BOOT-INF/classpath.idx，
      一旦丢失就启动不了。增量写入只动 static/assets，其余条目原样保留。

用法：
    sudo python3 recover_legacy_chunks.py [备份JAR ...]
    不带参数则自动扫描 /opt/camera-detection-system/app/*.bak-*
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

APP_DIR = "/opt/camera-detection-system/app"
CURRENT_JAR = os.path.join(APP_DIR, "time-bucket-demo.jar")
ASSET_PREFIX = "BOOT-INF/classes/static/assets/"


def log(msg):
    print(msg, flush=True)


def list_backups():
    if len(sys.argv) > 1:
        return sys.argv[1:]
    try:
        names = sorted(
            (n for n in os.listdir(APP_DIR) if ".bak-" in n),
            key=lambda n: os.path.getmtime(os.path.join(APP_DIR, n)),
            reverse=True,
        )
    except OSError:
        return []
    return [os.path.join(APP_DIR, n) for n in names]


def version_of(path):
    m = re.search(r"\.bak-([0-9_]+)$", os.path.basename(path))
    return m.group(1) if m else "unknown"


def read_assets(jar_path):
    """返回 {文件名: 字节内容}，只取 static/assets 下的 js/css。"""
    out = {}
    try:
        with zipfile.ZipFile(jar_path) as z:
            for info in z.infolist():
                if not info.filename.startswith(ASSET_PREFIX):
                    continue
                rel = info.filename[len(ASSET_PREFIX):]
                if "/" in rel:          # 只取平铺的（archive 子目录不要）
                    continue
                if not rel.endswith((".js", ".css")):
                    continue
                out[rel] = z.read(info.filename)
    except (zipfile.BadZipFile, OSError) as e:
        log("  ! 读取失败 %s: %s" % (os.path.basename(jar_path), e))
    return out


def main():
    backups = list_backups()
    if not backups:
        log("没有可用的备份 JAR")
        return 1
    log("=== 将从 %d 个备份中恢复历史 chunk ===" % len(backups))

    if not os.path.isfile(CURRENT_JAR):
        log("当前 JAR 不存在: %s" % CURRENT_JAR)
        return 1

    # 当前 JAR 已有的文件
    with zipfile.ZipFile(CURRENT_JAR) as z:
        current_names = set(z.namelist())
        current_assets = {
            n[len(ASSET_PREFIX):]: z.read(n)
            for n in current_names
            if n.startswith(ASSET_PREFIX)
            and "/" not in n[len(ASSET_PREFIX):]
            and n.endswith((".js", ".css"))
        }
        # 当前 JAR 的 index.html 引用哪个入口 → 那就是"当前版本"
        cur_entry = None
        if "BOOT-INF/classes/static/index.html" in current_names:
            html = z.read("BOOT-INF/classes/static/index.html").decode("utf-8", "replace")
            m = re.search(r"assets/index-([A-Za-z0-9_-]+)\.js", html)
            if m:
                cur_entry = m.group(1)
        log("  当前 JAR 的入口版本: %s" % (cur_entry or "未知"))

    # ★ 权威的「当前版本文件集」：优先用**本次部署用的源 JAR**（/tmp 下的），
    #   它就是当前线上版本的原始产物，文件集最准确。
    #   没有源 JAR 时，退回用当前 JAR 的 index.html + 入口文件推断。
    cur_files = set(current_assets.keys())
    src_jar = "/tmp/time-bucket-demo.jar"
    if os.path.isfile(src_jar):
        try:
            with zipfile.ZipFile(src_jar) as zs:
                src_assets = {
                    n[len(ASSET_PREFIX):]
                    for n in zs.namelist()
                    if n.startswith(ASSET_PREFIX)
                    and "/" not in n[len(ASSET_PREFIX):]
                    and n.endswith((".js", ".css"))
                }
            if src_assets:
                cur_files = src_assets
                log("  当前版本文件集来源: %s（%d 个文件）" % (src_jar, len(cur_files)))
        except (zipfile.BadZipFile, OSError):
            pass

    versions = {}      # 文件名 → 版本号
    for name in current_assets:
        # 当前版本文件 → current；其余（历史遗留）→ 稍后由备份确定归属
        versions[name] = "current" if name in cur_files else "pending"

    recovered = 0
    for bak in backups:
        if not os.path.isfile(bak):
            continue
        ver = version_of(bak)
        assets = read_assets(bak)
        if not assets:
            continue
        added = 0
        for name, data in assets.items():
            if name in current_assets:
                # 已存在：
                #  - current 的不动（当前版本文件，不能被历史备份"抢走"标记）
                #  - pending 的按这个备份确定归属（它就是从该备份来的）
                if versions.get(name) == "pending":
                    versions[name] = ver
                continue
            current_assets[name] = data
            versions[name] = ver
            added += 1
        recovered += added
        log("  %s: %d 个静态文件（新增 %d）" % (ver, len(assets), added))

    # 兜底：仍是 pending 的（所有备份里都没有）→ 归为最旧版本，避免被误当 current
    for name in list(versions.keys()):
        if versions[name] == "pending":
            versions[name] = "unknown"

    log("")
    log("=== 合并结果 ===")
    log("  新恢复文件数: %d" % recovered)
    log("  assets 文件总数: %d" % len(current_assets))

    # 写 .versions 清单
    versions_blob = ("\n".join(
        "%s=%s" % (k, versions[k]) for k in sorted(versions)
    ) + "\n").encode("utf-8")

    # ---------- 增量写回 JAR ----------
    log("")
    log("=== 增量写回 JAR（不改动其它条目）===")
    tmp_jar = CURRENT_JAR + ".tmp"
    with zipfile.ZipFile(CURRENT_JAR) as zin, \
         zipfile.ZipFile(tmp_jar, "w", zipfile.ZIP_DEFLATED) as zout:
        # 只写**非 assets** 的条目 —— assets 全部在下面统一重写。
        # ⚠️ 若在这里也写 assets，会产生**重复条目**
        #    （zipfile 警告 Duplicate name，读取时取最后一个，行为不确定）。
        for item in zin.infolist():
            name = item.filename
            if name.startswith(ASSET_PREFIX):
                continue
            zout.writestr(item, zin.read(name))
        # 写入新的 assets
        for fname, data in current_assets.items():
            zout.writestr(ASSET_PREFIX + fname, data)
        # 清单
        zout.writestr(ASSET_PREFIX + ".versions", versions_blob)

    # 校验：MANIFEST 与启动类必须还在
    with zipfile.ZipFile(tmp_jar) as z:
        manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
        if "JarLauncher" not in manifest or "Start-Class" not in manifest:
            log("  ! 警告：MANIFEST 异常")
        else:
            log("  ✓ MANIFEST 正常（JarLauncher / Start-Class 保留）")
        if "BOOT-INF/classpath.idx" in z.namelist():
            log("  ✓ BOOT-INF/classpath.idx 保留")
        else:
            log("  ! 警告：classpath.idx 丢失")

    os.replace(tmp_jar, CURRENT_JAR)
    os.chmod(CURRENT_JAR, 0o644)
    log("  ✓ 已更新 JAR: %s" % CURRENT_JAR)
    log("  体积: %.1f MB" % (os.path.getsize(CURRENT_JAR) / 1024 / 1024))

    # 验证用户报错的 chunk
    log("")
    log("=== 验证用户报错的 chunk ===")
    with zipfile.ZipFile(CURRENT_JAR) as z:
        names = z.namelist()
        for n in ["ImagesView-3Im0_kdo.js", "ConfigView"]:
            hit = [x for x in names if ASSET_PREFIX + n in x or x.endswith("/" + n)]
            log("  %s -> %s" % (n, ("✓ " + hit[0]) if hit else "✗ 未找到"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
