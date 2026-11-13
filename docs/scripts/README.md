# 运维与验证脚本索引

> 目录：`docs/scripts/`（一次性排查/迁移/修复脚本已随 2026-11-05 项目精简移除，需要时从 git 历史找回；
> 2026-11-12 又退役 4 个，见 `docs/archive/scripts-retired/`）
> **约定**：
> 1. 脚本内**不写明文口令**，一律从环境变量读取（`MYSQL_PASSWORD` / `SUDO_PASSWORD` / `SSH_PASSWORD` / `CAMERA_ADMIN_PASSWORD` / `GITHUB_TOKEN`）。
> 2. Shell 脚本必须 **LF 行尾**（`.gitattributes` 强制；CRLF 传到 Linux 会报 `set: pipefail: invalid option name`）。
> 3. **同名脚本以 `installer/linux/scripts/` 为唯一真源，本目录不保留任何副本。**
>    2026-11-12 已删除 `jar_manifest.py` / `prune_legacy_chunks.py` / `recover_legacy_chunks.py` 三份副本
>    —— 它们曾与 installer 逐字节一致，靠人工同步维持；本仓库栽过「副本漂移、测的是旧版」，
>    而增量部署正确性直接依赖 `jar_manifest.py`。**不要再往本目录拷第二份。**

---

## 一、部署与发版（核心链路）

| 脚本 | 用途 |
|---|---|
| `installer/linux/scripts/deploy_jar.sh` | 部署到服务器：按扩展名自动识别 zip=增量 / jar=全量；原子替换 + 健康检查 + **失败自动回滚** + 历史 JAR 自动保留 3 份（`JAR_BACKUP_KEEP`）。用法 `SUDO_PASSWORD='xxx' bash installer/linux/scripts/deploy_jar.sh /tmp/update-*.zip`。**本目录不再保留副本**：曾出现副本漂移到旧路径（`/opt/...`）且缺保留策略，实际部署用的是旧逻辑 |
| `installer/linux/scripts/jar_manifest.py` | 轻量增量发版核心（构建端/部署端共用）：`make-update` 产 2.1MB update 包、`merge` 与线上 fat jar 在线合成（lib 指纹不一致 exit 2 强制走全量）。⚠️ **真源在 installer，本目录无副本** |
| `upload_jar_b64.py` | 上传大文件到服务器的**唯一可行通道**（base64 分块 + 普通命令通道；scp/SFTP/管道直传均不可用）。Git Bash 需 `MSYS_NO_PATHCONV=1` |
| `push_via_github_api.py` | GitHub Git Data API 推送（原生 `git push` 被代理拦截 502 时的备选；支持多提交链；>40MB blob 复用远端 sha）。令牌从 `GITHUB_TOKEN` 读取 |

## 二、运维工具

| 脚本 | 用途 |
|---|---|
| `installer/linux/scripts/cleanup-test-cameras.sh` | **测试相机数据每小时增量清理**（装机到 `/main/app/dist/scripts/cleanup-test-cameras.sh` + `/etc/cron.d/cleanup-test-camera-images`，`15 * * * *`）。2026-11-12 由 `cleanup-zk54-incremental.sh` 改名 —— 原名像一次性脚本，实际是**在跑的通用任务**。★ 按数据库字段 `camera_type` 判定：REAL=真实相机（**不清理**）/ TEST=测试相机（清理）。保护集合 = `dim_camera` 的 REAL ∪ `camera_status` 的 REAL ∪ `PROTECTED_CODES`（任一表标 REAL 即保护）。三道防线：保护集合为空即中止(3)、单轮待删超阈值即中止(4)、清理后自检 REAL 数据是否减少。删目录「先 `mv` 到中转区再 `rm -rf`」防 `ENOTEMPTY`。口令自读 `/etc/default/camera-detection`，**不写进 cron**。日志文件仍叫 `cleanup_zk54.log`（logrotate 按路径配置，改名会让新日志脱离轮转） |
| `installer/linux/scripts/recover_legacy_chunks.py` | **从历史 JAR 备份恢复旧版前端 chunk**（部署后旧页面才能继续用）。增量写 JAR；**必须在部署完成后执行并重启服务**；绝不能 `jar cf` 重打包（丢 JarLauncher）。⚠️ 真源在 installer |
| `installer/linux/scripts/prune_legacy_chunks.py` | 按 `.versions` 清单淘汰过旧静态版本（默认保留 3 版）。⚠️ 真源在 installer |
| `sanitize_secrets.py` | 全仓库明文口令扫描/脱敏（CI 的口令扫描规则同源） |
| `sync_static_keep_legacy.py` | 把 `frontend/dist` 同步到后端 `static/` —— **只补不删**（保留历史 chunk 防旧标签页失效）。⚠️ 唯一入口；旧的 `sync_static.py` 会删旧 chunk（「旧页面点不动」根因），已退役到 `docs/archive/scripts-retired/`。路径按脚本位置推导，换机器无需改代码 |
| `git_slim.sh` | **仓库瘦身**：删除已出库构建产物的历史（`dist-release` 383MB + `static` 44MB），重写提交历史。默认 dry-run 只报数字；`--apply` 才执行，且自动 bundle 备份 + 打 `backup/pre-slim-*` 标签，**不自动强推**。要求工作区干净（有未提交改动直接退出码 3） |

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
| `verify_camera_kpi_browser.cjs` | 相机状态页 KPI 口径回归（9 项）：断言文案是「累计图片/累计高危缺陷」且**不再出现「近批次」**、口径说明可见、数值与 `camera-summaries` 接口一致（允许实时上传漂移 ≤ 30）、0 JS 错误。★ 这两个 KPI **不带时间参数 = 全部历史**，且**计入测试相机**，与产线统计（按时间窗、只算真实相机）不可直接对比 —— 曾因文案写「近批次」被当成数据错误（4337 vs 2411） |
| `verify_camera_status_browser.cjs` | 相机状态页回归：各产线的【累计图片】【累计高危缺陷】之和 **=== 不筛选总数**、重名相机的图片数不被共享、0 JS 错误（11 项）。★ Element Plus 下拉是 toggle 语义，连续切换不可靠 → 脚本每次筛选前重新加载页面。⚠️ 累加断言用「少算严格报错 + 多算 2% 时序容差」：总数在遍历产线前取、遍历期间现场持续上传，严格相等必然假红（实测差 5~96 条） |
| `verify_login_flow_browser.cjs` | 登录回跳流程回归（12 项）：凭据错误返回 **400 而非 401**（否则拦截器当会话过期，把回跳上下文改写成默认落地页）、登录后回到原页面。★ 回归脚本的 401 豁免正则必须写 `status code 401`，axios 实际文本带 `code` |
| `verify_license_browser.cjs` | 服务器授权页回归（10 项）：状态横幅、机器码格式、**页面显示与 `/api/license` 返回逐项一致**、上传入口、0 JS 错误 |

**登录辅助**：`browser-helper.cjs` 封装登录，所有验证脚本必须用它 —— 三个实测坑：
`page.type()` 在 password 框静默失效（用 `keyboard.type()`）；追加而非覆盖（先 Ctrl+A 清空）；
evaluate 内 value setter 会被 v-model 同步回去（用真实键盘事件）。
统一约束：需 `CAMERA_ADMIN_PASSWORD`；Node 变量必须显式传参进 `page.evaluate`；
401 场景清 token，进登录页前必须先清 token（路由守卫会重定向）。

## 四、服务端运维脚本（真源在 `installer/linux/scripts/`）

这一组是**装机后跑在服务器上**的脚本，本目录**没有任何副本**（2026-11-12 起全部只留 installer 一份）。
改它们要重新打包发布，改完必须现场验证。

### 4.1 调度总览

| 脚本 | 触发 | 作用 | 事故点 |
|---|---|---|---|
| `backup-database.sh` | systemd timer `camera-detection-backup.timer`，每日 **01:00** | 全量 mysqldump（zstd + sha256，留 14 份）+ binlog 增量（留 14 天）+ 图片热窗口（近 30 天，**排除 TEST 相机**，总量上限 `IMAGE_BACKUP_MAX_GB`=20G）→ 跨盘副本 `/var/backups` | 失败写 `BACKUP_FAILED` 告警；口令用 `MYSQL_PWD` 传，不进 `ps`/`auth.log`。⚠️ 2026-11-07：不加 TEST 排除时副本 3.9G vs 源 283M |
| `restore-database.sh` | 手工 | 恢复备份到临时库并逐表比对行数 | 目标库名白名单 `restore_check_*` / `ZZTEST_*`，指定 `wms-new` 直接拒绝 |
| `check-disk-usage.sh` | cron `*/30 * * * *` | 分区水位（`/ /main`）+ 备份新鲜度（26h）+ 容量预测（REAL 相机日增推算，<180 天告警） | 同类型告警未恢复**只 UPDATE**；判定用 `alert_id` 不是 `ROW_COUNT()`。⚠️ 2026-11-13 前默认值是 `/ /var /data`，而 `/data` 迁移后已非挂载点 → `df` 返回 40G 系统盘，**500G 数据盘从未被巡检** |
| `cleanup-defect-images.sh` | cron `30 3 * * *` | 按 `{yyyyMMdd}` 目录删 730 天前的图片，并删对应库记录 | **磁盘图片文件的唯一删除者**；先删库后删盘 |
| `cleanup-test-cameras.sh` | cron `15 * * * *` | 删 `camera_type=TEST` 相机的图片与记录 | 三道防线：双表 OR 保护集 / 保护集为空中止(3) / 单轮超阈值中止(4)。删目录用「先 `mv` 到中转区再 `rm -rf`」防 `ENOTEMPTY`；`CLEANUP_FAILED` 同行更新、成功自动恢复 |

> ⚠️ `/etc/cron.d/*` 是 **644 全局可读**，绝不写 `MYSQL_PASSWORD`。脚本自读 `/etc/default/camera-detection`。
> 曾因把口令写进 cron 泄漏过（`sudo -u nobody cat` 实测可读）。

### 4.2 生命周期与故障处置

| 脚本 | 触发 | 作用 |
|---|---|---|
| `install.sh` | 手工（root） | 全新安装：建用户、装 JAR、写 systemd、装 cron/timer、装 logrotate |
| `init-database.sh` | 手工 | 按序执行 `sql/deploy/01~04` 建库 |
| `run.sh` | systemd `ExecStart` | 启动入口，`source /etc/default/camera-detection` 组装 JVM 参数 |
| `service.sh` | 手工 | `start/stop/restart/status/logs/version/health/config-check/rollback/cleanup` |
| `verify.sh` | 手工（**必须 root + 正确口令**） | 42 项自检（统一布局；未迁移老布局 35 项）：环境、配置、目录、保留期口径、cron 无明文口令、**cron 脚本路径（必须是 `${INSTALL_DIR}/scripts/`，防旧副本）**、备份新鲜度、**binlog 副本新鲜度**、图片副本无 TEST 相机、服务、鉴权、上报链路、授权可用性 |
| `reset-password.sh` | 手工 | 重置管理员口令（前端无自助改密端点） |

`verify.sh` 的口径检查会比对 **cron `KEEP_DAYS` vs `application.yml` `retain-days`**；
外部配置没写 `app.archive.*` 时回退读 JAR 内 `BOOT-INF/classes/application.yml`
（Spring 外部配置按 key 合并，不是整文件替换 —— 只查外部文件会误判成配置缺失）。
