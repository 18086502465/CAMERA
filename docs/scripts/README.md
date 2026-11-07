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
| `installer/linux/scripts/cleanup-zk54-incremental.sh` | **测试相机数据每小时增量清理**（装机到 `/usr/local/bin/cleanup-zk54-incremental.sh` + `/etc/cron.d/cleanup-test-camera-images`，`15 * * * *`）。★ 按数据库字段 `camera_type` 判定：REAL=真实相机（**不清理**）/ TEST=测试相机（清理）。保护集合 = `dim_camera` 的 REAL ∪ `camera_status` 的 REAL ∪ `PROTECTED_CODES`（任一表标 REAL 即保护）。三道防线：保护集合为空即中止(3)、单轮待删超阈值即中止(4)、清理后自检 REAL 数据是否减少。删目录「先 `mv` 到中转区再 `rm -rf`」防 `ENOTEMPTY`。口令自读 `/etc/default/camera-detection`，**不写进 cron**。⚠️ 本目录**不再保留副本**（旧的下划线拷贝已删，避免与 installer 版漂移） |
| `recover_legacy_chunks.py` | **从历史 JAR 备份恢复旧版前端 chunk**（部署后旧页面才能继续用）。增量写 JAR；**必须在部署完成后执行并重启服务**；绝不能 `jar cf` 重打包（丢 JarLauncher） |
| `prune_legacy_chunks.py` | 按 `.versions` 清单淘汰过旧静态版本（默认保留 3 版） |
| `sanitize_secrets.py` | 全仓库明文口令扫描/脱敏（CI 的口令扫描规则同源） |
| `sync_static_keep_legacy.py` | 把 `frontend/dist` 同步到后端 `static/` —— **只补不删**（保留历史 chunk 防旧标签页失效）。⚠️ 不要用 `sync_static.py`：那个会删旧 chunk，正是「部署后旧页面点不动」的根因 |

## 三、验证工具与浏览器回归

| 脚本 | 用途 |
|---|---|
| `api_snapshot.py` | 48 只读端点快照录制 + 归一化 diff（数字/时间归一化，基线在服务器 `api-snapshot/before-ddd`） |
| `compare_structure.py` | 接口快照**键结构树**比对（只比键路径忽略值）→ "行为不变"的硬指标 |
| `check_sql_columns.py` | **SQL 列名静态校验**：用 information_schema 真实表结构反查 SQL 引用的列。⚠️ 文本比对抓不到列名错（实测 `w.station_id` 应为 `w.workstation_id`，文本一致但整页 500） |
| `check_entity_vs_ddl.py` | **实体 ↔ 建库脚本列名对账**：`FactAnomalyAlert` 等实体字段 vs `01_init_schema_nodb.sql` 的列，找出「只靠 `04_align_entity_columns.sql` 裸 ALTER 补上」的差异。全新库能跑不代表现场库能跑 |
| `probe_api_paths.sh` | 前端 `api/*.js` 路径与后端端点核对（曾发现 `/users` vs `/user` 404） |
| `gen_api_doc.py` | 从 Controller 源码再生成《接口文档.md》（`python docs/scripts/gen_api_doc.py`） |
| `check_doc_links.py` | **文档一致性守卫**：校验全部相对链接 / 锚点是否有效、`docs/` 根是否混入历史稿、源目录是否放了 .md。提交前跑一遍（`python docs/scripts/check_doc_links.py`，失败退出码 1） |
| `test_cleanup_protect_set.sh` | **清理脚本保护集合逻辑回归**：从真实脚本抽取代码段跑 6 个场景（两表各标/单表标记/运维加码/全误标/多台）。★ 起因：`printf '%s' \| tr` 输出的最后一行无换行 → `while read` 丢弃最后一个相机 → **真实相机被静默丢出保护集合**。本地跑，无需服务器 |
| `verify_camera_type_guards.sh` | **实机验证三道防误删防线 + 正向清理**：造 ZZTEST 数据 → 验证被清 → 验证真实相机数据分毫不动 → 验证保护集合为空中止(3)/超阈值中止(4) → 自动还原。服务器上以 root 执行 |
| `verify_camera_type_browser.cjs` | 前端浏览器回归：相机类型列、真实/测试标签、编辑弹窗下拉与回填、0 JS 错误（9 项） |
| `verify_line_analytics_browser.cjs` | 产线统计页回归：筛选产线与不筛选的检测总数**必须一致**、数据源一致、图片通道无良率卡片、趋势图无良率线、0 JS 错误（11 项） |
| `verify_camera_status_browser.cjs` | 相机状态页回归：各产线的【近批次图片】【高危缺陷】之和 **=== 不筛选总数**、重名相机的图片数不被共享、0 JS 错误（11 项）。★ Element Plus 下拉是 toggle 语义，连续切换不可靠 → 脚本每次筛选前重新加载页面。⚠️ 累加断言用「少算严格报错 + 多算 2% 时序容差」：总数在遍历产线前取、遍历期间现场持续上传，严格相等必然假红（实测差 5~96 条） |
| `verify_login_flow_browser.cjs` | 登录回跳流程回归（12 项）：凭据错误返回 **400 而非 401**（否则拦截器当会话过期，把回跳上下文改写成默认落地页）、登录后回到原页面。★ 回归脚本的 401 豁免正则必须写 `status code 401`，axios 实际文本带 `code` |
| `验证脚本-P0修复回归.cjs` | 用户接口路径、告警页数据、检测记录页数据源与「—」显示（13 项断言） |
| `验证脚本-403拦截器行为.cjs` | 拦截器分流：403 ACCESS_DENIED 不跳转 / 401 跳登录（9 项断言） |
| `verify-legacy-nav.cjs` | 模拟"旧版 index.html"场景：旧 chunk 可访问 + 导航正常（配合 `recover_legacy_chunks.py`） |

**登录辅助**：`browser-helper.cjs` 封装登录，所有验证脚本必须用它 —— 三个实测坑：
`page.type()` 在 password 框静默失效（用 `keyboard.type()`）；追加而非覆盖（先 Ctrl+A 清空）；
evaluate 内 value setter 会被 v-model 同步回去（用真实键盘事件）。
统一约束：需 `CAMERA_ADMIN_PASSWORD`；Node 变量必须显式传参进 `page.evaluate`；
401 场景清 token，进登录页前必须先清 token（路由守卫会重定向）。

## 四、服务端运维脚本（真源在 `installer/linux/scripts/`）

这一组是**装机后跑在服务器上**的脚本，本目录没有副本（除 4 个部署脚本外）。
改它们要重新打包发布，改完必须现场验证。

### 4.1 调度总览

| 脚本 | 触发 | 作用 | 事故点 |
|---|---|---|---|
| `backup-database.sh` | systemd timer `camera-detection-backup.timer`，每日 **01:00** | 全量 mysqldump（zstd + sha256，留 14 份）+ binlog 增量（留 14 天）+ 图片热窗口（近 30 天，**排除 TEST 相机**，总量上限 `IMAGE_BACKUP_MAX_GB`=20G）→ 跨盘副本 `/var/backups` | 失败写 `BACKUP_FAILED` 告警；口令用 `MYSQL_PWD` 传，不进 `ps`/`auth.log`。⚠️ 2026-11-07：不加 TEST 排除时副本 3.9G vs 源 283M |
| `restore-database.sh` | 手工 | 恢复备份到临时库并逐表比对行数 | 目标库名白名单 `restore_check_*` / `ZZTEST_*`，指定 `wms-new` 直接拒绝 |
| `check-disk-usage.sh` | cron `*/30 * * * *` | 分区水位（`/ /var /data`）+ 备份新鲜度（26h）+ 容量预测（REAL 相机日增推算，<180 天告警） | 同类型告警未恢复**只 UPDATE**；判定用 `alert_id` 不是 `ROW_COUNT()` |
| `cleanup-defect-images.sh` | cron `30 3 * * *` | 按 `{yyyyMMdd}` 目录删 730 天前的图片，并删对应库记录 | **磁盘图片文件的唯一删除者**；先删库后删盘 |
| `cleanup-zk54-incremental.sh` | cron `15 * * * *` | 删 `camera_type=TEST` 相机的图片与记录 | 三道防线：双表 OR 保护集 / 保护集为空中止(3) / 单轮超阈值中止(4)。删目录用「先 `mv` 到中转区再 `rm -rf`」防 `ENOTEMPTY`；`CLEANUP_FAILED` 同行更新、成功自动恢复 |

> ⚠️ `/etc/cron.d/*` 是 **644 全局可读**，绝不写 `MYSQL_PASSWORD`。脚本自读 `/etc/default/camera-detection`。
> 曾因把口令写进 cron 泄漏过（`sudo -u nobody cat` 实测可读）。

### 4.2 生命周期与故障处置

| 脚本 | 触发 | 作用 |
|---|---|---|
| `install.sh` | 手工（root） | 全新安装：建用户、装 JAR、写 systemd、装 cron/timer、装 logrotate |
| `init-database.sh` | 手工 | 按序执行 `sql/deploy/01~04` 建库 |
| `run.sh` | systemd `ExecStart` | 启动入口，`source /etc/default/camera-detection` 组装 JVM 参数 |
| `service.sh` | 手工 | `start/stop/restart/status/logs/version/health/config-check/rollback/cleanup` |
| `verify.sh` | 手工（**必须 root + 正确口令**） | 32 项自检：环境、配置、目录、保留期口径、cron 无明文口令、备份新鲜度、图片副本无 TEST 相机、服务、鉴权、上报链路 |
| `reset-password.sh` | 手工 | 重置管理员口令（前端无自助改密端点） |

`verify.sh` 的口径检查会比对 **cron `KEEP_DAYS` vs `application.yml` `retain-days`**；
外部配置没写 `app.archive.*` 时回退读 JAR 内 `BOOT-INF/classes/application.yml`
（Spring 外部配置按 key 合并，不是整文件替换 —— 只查外部文件会误判成配置缺失）。
