# 已退役脚本（2026-11-12 归档）

> 本目录**不是**规范的一部分，脚本**不再维护、不要再跑**。留在这里只为可追溯：
> 当年为什么这么写、踩了什么坑，比脚本本身值钱。
> 现行脚本索引见 `docs/scripts/README.md`。

| 脚本 | 退役日期 | 为什么不能再用 | 替代者 |
|---|---|---|---|
| `sync_static.py` | 2026-11-12 | 同步时会**删掉旧 chunk**，正是「部署后旧页面点不动」的根因（2026-11-04 事故）。历史 index.html 引用的 hash 文件被删 → 老标签页 router 静默中断 | `docs/scripts/sync_static_keep_legacy.py`（只补不删） |
| `verify-legacy-nav.cjs` | 2026-11-12 | 模拟「旧版 index.html」场景，验证对象是上面那套人工 chunk 管理。chunk 保留已改由 `build-release.sh` 内建（保留 3 版）+ `recover/prune_legacy_chunks.py` 承担，脚本前提消失 | 无（能力被构建流程吸收） |
| `验证脚本-403拦截器行为.cjs` | 2026-11-12 | 一次性验证 401/403 分流修复（9 项断言）。该行为已固化进 12 项 `verify_login_flow_browser.cjs`，重复覆盖 | `docs/scripts/verify_login_flow_browser.cjs` |
| `验证脚本-P0修复回归.cjs` | 2026-11-12 | 一次性验证 P0 修复闭环（13 项）。被验证的路径/数据源问题已由 `probe_api_paths.sh` 与产线统计回归长期守卫 | `probe_api_paths.sh`、`verify_line_analytics_browser.cjs` |

## 顺手删掉的三份副本

同一批清理里还删除了 `docs/scripts/` 下的 `jar_manifest.py`、`prune_legacy_chunks.py`、
`recover_legacy_chunks.py` —— 它们与 `installer/linux/scripts/` 下同名文件**逐字节一致**，
属"开发侧副本"。本仓库栽过「副本漂移、测的是旧版」，**增量部署正确性直接依赖 `jar_manifest.py`**，
留着两份就是留一个必定会踩的坑。

现约定：**同名脚本以 `installer/linux/scripts/` 为唯一真源**，`docs/scripts/` 不再保留任何副本。
