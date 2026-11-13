# CAMERA

产线相机 · **产线缺陷在线AI检测系统**

产线上的工业相机上报状态心跳、检测计数与缺陷图片，后端聚合为产线统计看板，
并提供主数据配置、设备令牌管理、运维巡检与服务器资源监控能力。

---

## 技术栈

| 组件 | 技术 | 目录 |
|---|---|---|
| 后端 | Spring Boot 2.7.18 / Java 8 / MyBatis-Plus / MySQL 8 | `fact-product/` |
| 前端 | Vue 3.5 + Vite 5 + Element Plus 2.7 + ECharts 5.6 | `frontend/` |
| 部署 | Linux（systemd + bash） | `installer/linux/` |

> Windows 版（`installer/scripts/*.ps1`、`installer/linux/camera-detection-system.iss`）已冻结不再维护，脚本保留仅为历史参考。

## 目录结构

```
fact-product/    后端源码（七业务域 DDD：iam/masterdata/device/defect/inspection/analytics/system + shared/init）
  └ src/main/resources/sql/deploy/   建库脚本真源（01→02→03→04 全新库顺序执行；05/06 为增量补丁）
frontend/        前端源码（Vue3 单页应用）
installer/       Linux 安装脚本、systemd 单元、verify.sh 自检、配置模板
docs/            交付文档（见 docs/README.md：现行规范 / archive 历史稿 / scripts 脚本）
dist-release/    构建产物输出目录（由 installer/linux/build-release.sh 生成，全目录不入库）
```

## 核心能力

| 模块 | 说明 |
|---|---|
| **设备接入** | 心跳上报、批量检测上报、缺陷图片上传；设备令牌走 `cfg_camera_token` |
| **缺陷映射** | 相机 + 编码 + 产品 + 生效期四维匹配，产品专属优先；未映射返回 422 `UNMAPPED` |
| **统计看板** | 产量/良率/缺陷分布、产线排名、班次对比、部件级分析、30 分钟聚合与日聚合 |
| **主数据配置** | 工厂 → 产线 → 工位 → 相机的层级配置，含依赖校验与 Excel 批量导入 |
| **设备令牌** | 每台相机独立令牌，SHA-256 存储，支持启用/停用与有效期 |
| **异常告警** | 相机故障、计数不符、CPU/内存/温度超阈值五类，经事件落库 + 页面展示 |
| **数据治理** | 每日全量备份（zstd + SHA-256）+ binlog 增量 + 图片热窗口备份，跨盘副本；保留期归档；磁盘水位与容量预测告警 |
| **服务器监控** | 本机 CPU / 内存 / 分区容量 / 磁盘读写速率与 IOPS 实时指标（OSHI 6.9.3） |

## 快速开始

### 构建

```bash
# 后端
cd fact-product && mvn -B -DskipTests clean package   # target/time-bucket-demo.jar
cd fact-product && mvn -B test                        # 全量测试（含 ArchUnit 架构守护），必须全绿

# 前端
cd frontend && npm ci && npm run build      # 只用 npm ci，与 CI 严格一致（不要用 npm install）
```

> ⚠️ **`static/` 是构建产物，已出库**：`fact-product/src/main/resources/static/` 不在 git 里
> （Vite 产物每次构建 140 个文件全改名，入库只制造噪音）。`npm run build` 的 `postbuild` 会在
> static **为空**时自动填充一次；平时打包走 `installer/linux/build-release.sh`，它独占同步与历史 chunk 版本链。
> 手工同步用 `docs/scripts/sync_static_keep_legacy.py`（**只补不删**），别整目录 `rm -rf`。
> 已退役的 `sync_static.py` 会删旧 chunk（「部署后旧页面点不动」的根因），见 `docs/archive/scripts-retired/`。

### 发版（轻量增量，日常推荐）

```bash
# 构建机：产出 update-*.zip（实测 10~11MB，随历史 chunk 数量增长）
bash installer/linux/build-release.sh --update-only
# 服务器：执行部署（失败自动回滚）。脚本随包安装到 /main/app/dist/scripts/
SUDO_PASSWORD='xxx' bash /main/app/dist/scripts/deploy_jar.sh /tmp/update-*.zip
```

> `installer/linux/scripts/` 是**唯一真源**，`docs/scripts/` 自 2026-11-12 起不留任何副本
> （曾因副本漂移导致"测的是旧版"）。构建机直接用 `installer/linux/scripts/deploy_jar.sh`；
> 增量模式依赖**同目录**的 `jar_manifest.py`。

### 建库

按顺序执行（真源 `fact-product/src/main/resources/sql/deploy/`）：

```bash
sql/deploy/01_init_schema_nodb.sql
sql/deploy/02_final_schema_p0.sql
sql/deploy/03_system_settings.sql
sql/deploy/04_align_entity_columns.sql
sql/deploy/05_camera_type_migration.sql
```

> ⚠️ `02_final_schema_p0.sql` 会 **DROP 并重建 21 张表**，生产库升级绝不能跑全量流程。
> **05 必须进全新库流程**（不是可选补丁）：它给两张相机表加 `camera_type` 列，缺了
> 清理脚本查不到"哪些相机不能删"，保护集恒为空 → 每小时清理直接中止并告警。
> 用 `scripts/init-database.sh` 建库时它会自动按 01→05 执行，且**库非空时会拒绝**（需 `--force`）。

已有库按需追加增量补丁（幂等，可重复执行，不进全新库流程）：

| 脚本 | 作用 |
|---|---|
| `06_index_tuning.sql` | `camera_images.capture_time` 索引（两处清理任务的判据字段） |

### 部署（首次/全量）

```bash
bash installer/linux/build-release.sh     # 组装 stage + tar.gz
# 解包后执行
sudo ./scripts/install.sh                 # 口令从环境变量 / /etc/default 读取
sudo bash scripts/verify.sh               # 自检（统一布局 41 项，未迁移老布局 35 项）
```

## 文档索引

**唯一入口：[`docs/README.md`](docs/README.md)** —— 现行规范清单、历史评审稿 `docs/archive/`、脚本索引
`docs/scripts/README.md`，以及「遇到什么问题查哪份」的场景导航，都在那一页。
文档份数以该页目录为准，此处不再硬编码数字（曾因写死"7 份"与实际不一致而失真）。

最常用的三份：

| 文档 | 内容 |
|---|---|
| [`docs/踩坑速查.md`](docs/踩坑速查.md) | 真实事故与根因（按时间编号，逐节累加），**改代码前必读** |
| [`docs/部署与运维手册.md`](docs/部署与运维手册.md) | 安装、升级、备份恢复、保留策略、故障排查 |
| [`docs/接口文档.md`](docs/接口文档.md) | 全部端点（`gen_api_doc.py` 自动生成，勿手改） |

## 已知限制

- **部分系统设置项存表但不生效**：只有 3 个告警阈值被业务读取，其余为展示性配置
- **服务器监控只覆盖本机**，历史曲线存进程内存，重启后清零
- **设备端输出格式建议用 JPEG**：PNG 实测可达 1.2 MB/张，会显著抬高磁盘占用
- **跨盘副本不是异地容灾**：`OFFSITE_DIR` 已从 `/data` 改到 `/var/backups`（两块物理盘，可抗单盘故障），
  但仍在同一台机器上，不抗整机/机房故障。真异地需另接远程存储
- **清理任务无心跳表**：任务是否执行只能靠日志判断，静默停摆无法主动告警（待做）
- **容量按 REAL 相机数线性增长**：实测 2 台 ≈67 MB/天（730 天约 48GB，500G 盘充裕）；
  扩到 10 台约 465GB 逼近上限，届时必须扩盘

## 默认账号

| 账号 | 口令 |
|---|---|
| `admin` | `123456`（全新部署库）/ `admin123`（现场机已改） |

> ⚠️ 正式投产前必须修改管理员口令。当前口令记录在
> `/etc/default/camera-detection`（权限 640 root），不入仓库。

## 许可

内部项目，未指定公开许可证。
