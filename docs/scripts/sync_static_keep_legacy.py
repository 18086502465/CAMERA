# -*- coding: utf-8 -*-
"""
把 frontend/dist 的前端产物同步到后端 static/ —— **只补不删**。

【为什么不用 docs/scripts/sync_static.py】
    那个脚本会**删除** static 里有、dist 里没有的旧 chunk。
    但本项目 2026-11-04 踩过一个大坑（见 docs/踩坑速查.md §4.1）：
    Vite chunk 名带内容哈希 → 每次构建全部改名 → 浏览器里开着的**旧页面**
    点懒加载路由 → 指向旧 chunk → 404 → "Failed to fetch dynamically
    imported module" → vue-router 导航 Promise 被 reject → **导航静默中断**
    （URL 不变、页面不刷新、无后端请求）。
    自动化每次新开页面永远测不出，只有跨部署的旧标签页才触发。
    → 旧 chunk **必须保留**，由 recover_legacy_chunks.py / prune_legacy_chunks.py
      按版本清理。

同步规则：
    ① dist 里有、static 里没有 → 拷贝过去，.versions 记为当前版本号
    ② 两边都有且内容一致     → 不动
    ③ 两边都有但内容不同     → 覆盖（文件名带 hash，正常不会发生；真发生了以 dist 为准）
    ④ static 有、dist 没有   → **保留**（历史 chunk，供旧标签页命中）
    ⑤ index.html            → 用 dist 的覆盖（入口 chunk 引用变了）
    ⑥ .versions             → 重建：本次新增/覆盖的文件记新版本号；
       原来的 "current" 落为它自己所属的历史版本号

版本号 = 本次入口 chunk 的 hash（index-XXXX.js 中的 XXXX），
与历史 .versions 里的写法（index-BX7S6dqU.js=BX7S6dqU）一致。
"""
import os
import re
import shutil
import sys

# 路径从脚本位置推导（本文件在 <repo>/docs/scripts/ 下，上溯 3 层即仓库根）。
# 原来写死 D:\031_45344camera\...，换机器/换目录就直接失效。
_REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DIST = os.path.join(_REPO, 'frontend', 'dist')
STATIC = os.path.join(_REPO, 'fact-product', 'src', 'main', 'resources', 'static')
ASSET_NAME = 'assets'
VERSIONS = '.versions'


def read_versions(path):
    """读 .versions → {文件名: 版本号}"""
    out = {}
    if os.path.isfile(path):
        with open(path, encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line or '=' not in line:
                    continue
                k, v = line.split('=', 1)
                out[k] = v
    return out


def entry_version(dist_assets):
    """从 dist/index.html 找入口 chunk 的 hash 作为本次版本号"""
    idx = os.path.join(DIST, 'index.html')
    if not os.path.isfile(idx):
        return 'unknown'
    with open(idx, encoding='utf-8') as f:
        m = re.search(r'/assets/index-([A-Za-z0-9_-]+)\.js', f.read())
    return m.group(1) if m else 'unknown'


def main():
    dist_assets = os.path.join(DIST, ASSET_NAME)
    static_assets = os.path.join(STATIC, ASSET_NAME)
    if not os.path.isdir(dist_assets):
        print('!! dist/assets 不存在：%s（先执行 npx vite build）' % dist_assets)
        return 2
    os.makedirs(static_assets, exist_ok=True)

    version = entry_version(dist_assets)
    versions_path = os.path.join(static_assets, VERSIONS)
    versions = read_versions(versions_path)
    print('本次构建版本号：%s' % version)

    dist_files = set(os.listdir(dist_assets))
    copied, unchanged, overwritten = 0, 0, 0

    for name in sorted(dist_files):
        src = os.path.join(dist_assets, name)
        dst = os.path.join(static_assets, name)
        if not os.path.isfile(src):
            continue
        if os.path.isfile(dst) and open(src, 'rb').read() == open(dst, 'rb').read():
            unchanged += 1
            versions[name] = 'current'
            continue
        existed = os.path.isfile(dst)
        # Windows 上只读文件会删不掉，先加写权限
        if existed:
            try:
                os.chmod(dst, 0o666)
            except Exception:
                pass
        shutil.copy2(src, dst)
        if existed:
            overwritten += 1
        else:
            copied += 1
        versions[name] = 'current'

    # 旧文件：原来标 current 的，落为它自己所属的历史版本号
    for name in list(versions.keys()):
        if versions[name] == 'current' and name not in dist_files:
            m = re.search(r'-([A-Za-z0-9_-]{8})\.(js|css)$', name)
            versions[name] = m.group(1) if m else 'unknown'

    # 写回 .versions
    with open(versions_path, 'w', encoding='utf-8', newline='\n') as f:
        for name in sorted(versions):
            f.write('%s=%s\n' % (name, versions[name]))

    # index.html
    src_idx = os.path.join(DIST, 'index.html')
    dst_idx = os.path.join(STATIC, 'index.html')
    if os.path.isfile(src_idx):
        shutil.copy2(src_idx, dst_idx)

    kept = len(set(os.listdir(static_assets)) - dist_files)
    print('拷贝新文件 %d 个，覆盖 %d 个，未变 %d 个' % (copied, overwritten, unchanged))
    print('保留历史 chunk %d 个（供旧标签页命中）' % kept)
    print('static/assets 共 %d 个文件' % len(os.listdir(static_assets)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
