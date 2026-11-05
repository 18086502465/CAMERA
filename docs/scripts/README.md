# 运维与验证脚本索引

> 目录：`docs/scripts/`（一次性排查/迁移/修复脚本已随 2026-11-05 项目精简移除，需要时从 git 历史找回）
> **约定**：
> 1. 脚本内**不写明文口令**，一律从环境变量读取（`MYSQL_PASSWORD` / `SUDO_PASSWORD` / `SSH_PASSWORD` / `CAMERA_ADMIN_PASSWORD` / `GITHUB_TOKEN`）。
> 2. Shell 脚本必须 **LF 行尾**（`.gitattributes` 强制；CRLF 传到 Linux 会报 `set: pipefail: invalid option name`）。
> 3. 同名脚本以 `installer/linux/scripts/` 为权威源（构建时同步进发布包），本目录为开发侧副本。

---

## 一、部署与发版（核心链路）

| 脚本 | 用途 |
|---|---|
| `deploy_jar.sh` | 部署到服务器：按扩展名自动识别 zip=增量 / jar=全量；原子替换 + 健康检查 + **失败自动回滚**。用法 `SUDO_PASSWORD='xxx' bash deploy_jar.sh /tmp/update-*.zip` |
| `jar_manifest.py` | 轻量增量发版核心（构建端/部署端共用）：`make-update` 产 2.1MB update 包、`merge` 与线上 fat jar 在线合成（lib 指纹不一致 exit 2 强制走全量） |
| `upload_jar_b64.py` | 上传大文件到服务器的**唯一可行通道**（base64 分块 + 普通命令通道；scp/SFTP/管道直传均不可用）。Git Bash 需 `MSYS_NO_PATHCONV=1` |
| `sync_static.py` | 把 `frontend/dist` 同步到后端 `static/` 并清理陈旧 chunk |
| `push_via_github_api.py` | GitHub Git Data API 推送（原生 `git push` 被代理拦截 502 时的备选；支持多提交链；>40MB blob 复用远端 sha）。令牌从 `GITHUB_TOKEN` 读取 |

## 二、运维工具

| 脚本 | 用途 |
|---|---|
| `cleanup_zk54_incremental.sh` | ZK54 测试相机数据每小时增量清理（服务器 `/usr/local/bin/` 同名副本 + root cron `15 * * * *`，幂等，只留 ZK23 白名单） |
| `recover_legacy_chunks.py` | **从历史 JAR 备份恢复旧版前端 chunk**（部署后旧页面才能继续用）。增量写 JAR；**必须在部署完成后执行并重启服务**；绝不能 `jar cf` 重打包（丢 JarLauncher） |
| `prune_legacy_chunks.py` | 按 `.versions` 清单淘汰过旧静态版本（默认保留 3 版） |
| `sanitize_secrets.py` | 全仓库明文口令扫描/脱敏（CI 的口令扫描规则同源） |

## 三、验证工具

| 脚本 | 用途 |
|---|---|
| `api_snapshot.py` | 48 只读端点快照录制 + 归一化 diff（数字/时间归一化，基线在服务器 `api-snapshot/before-ddd`） |
| `compare_structure.py` | 接口快照**键结构树**比对（只比键路径忽略值）→ "行为不变"的硬指标 |
| `check_sql_columns.py` | **SQL 列名静态校验**：用 information_schema 真实表结构反查 SQL 引用的列。⚠️ 文本比对抓不到列名错（实测 `w.station_id` 应为 `w.workstation_id`，文本一致但整页 500） |
| `probe_api_paths.sh` | 前端 `api/*.js` 路径与后端端点核对（曾发现 `/users` vs `/user` 404） |
| `gen_api_doc.py` | 从 Controller 源码再生成《接口文档.md》（`python docs/scripts/gen_api_doc.py`） |

## 四、浏览器回归（`docs/` 根 + 本目录）

| 脚本 | 覆盖内容 |
|---|---|
| `docs/验证脚本-P0修复回归.cjs` | 用户接口路径、告警页数据、检测记录页数据源与「—」显示（13 项断言） |
| `docs/验证脚本-403拦截器行为.cjs` | 拦截器分流：403 ACCESS_DENIED 不跳转 / 401 跳登录（9 项断言） |
| `verify-legacy-nav.cjs` | 模拟"旧版 index.html"场景：旧 chunk 可访问 + 导航正常（配合 `recover_legacy_chunks.py`） |

**登录辅助**：`browser-helper.cjs` 封装登录，所有验证脚本必须用它 —— 三个实测坑：
`page.type()` 在 password 框静默失效（用 `keyboard.type()`）；追加而非覆盖（先 Ctrl+A 清空）；
evaluate 内 value setter 会被 v-model 同步回去（用真实键盘事件）。
统一约束：需 `CAMERA_ADMIN_PASSWORD`；Node 变量必须显式传参进 `page.evaluate`；
401 场景清 token，进登录页前必须先清 token（路由守卫会重定向）。
