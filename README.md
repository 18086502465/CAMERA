# CAMERA

产线相机 · **产线缺陷在线AI检测系统**

产线上的工业相机上报状态心跳、检测计数与缺陷图片，后端聚合为产线统计看板，
并提供主数据配置、设备令牌管理、运维巡检与服务器资源监控能力。

---

## 技术栈

| 组件 | 技术 | 目录 |
|---|---|---|
| 后端 | Spring Boot 2.7.15 / Java 8 / MyBatis-Plus / MySQL 8 | `fact-product/` |
| 前端 | Vue 3.5 + Vite 5 + Element Plus 2.7 + ECharts 5.6 | `frontend/` |
| 部署 | Linux（systemd + bash） | `installer/linux/` |

> Windows 版（`installer/scripts/*.ps1`、`installer/linux/camera-detection-system.iss`）已冻结不再维护，脚本保留仅为历史参考。

## 目录结构

```
fact-product/    后端源码（六域 DDD：iam/masterdata/device/defect/inspection/analytics + system + shared）
  └ src/main/resources/sql/deploy/   四段建库脚本真源（01 → 02 → 03 → 04 顺序执行）
frontend/        前端源码（Vue3 单页应用）
installer/       Linux 安装脚本、systemd 单元、verify.sh 自检、配置模板
docs/            交付文档（架构/模块/接口/运维/使用）+ scripts/（运维与验证脚本）
dist-release/    构建产物输出目录（由 installer/linux/build-release.sh 生成，不入库）
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
| **数据治理** | 定时备份（zstd + SHA-256）、图片清理（先删库记录再删文件）、保留期归档 |
| **服务器监控** | 本机 CPU / 内存 / 分区容量 / 磁盘读写速率与 IOPS 实时指标（OSHI 6.9.3） |

## 快速开始

### 构建

```bash
# 后端
cd fact-product && mvn -B -DskipTests clean package   # target/time-bucket-demo.jar
cd fact-product && mvn -B test                        # 79 个用例（含 3 条架构守护）

# 前端
cd frontend && npm install && npm run build
```

> ⚠️ **前端产物必须双份同步**：`frontend/dist/*` → `fact-product/src/main/resources/static/`
> （用 `docs/scripts/sync_static.py`，漏了会导致打包出的 JAR 里没有前端页面）

### 发版（轻量增量，日常推荐）

```bash
bash installer/linux/build-release.sh --update-only    # 产出 ~2MB update-*.zip
SUDO_PASSWORD='xxx' bash deploy_jar.sh /tmp/update-*.zip  # 服务器增量部署，失败自动回滚
```

### 建库

按顺序执行四段脚本（真源 `fact-product/src/main/resources/sql/deploy/`）：

```bash
sql/deploy/01_init_schema_nodb.sql
sql/deploy/02_final_schema_p0.sql
sql/deploy/03_system_settings.sql
sql/deploy/04_align_entity_columns.sql
```

> ⚠️ `02_final_schema_p0.sql` 会 **DROP 并重建 21 张表**，生产库升级绝不能跑全量流程。

### 部署（首次/全量）

```bash
bash installer/linux/build-release.sh     # 组装 stage + tar.gz
# 解包后执行
sudo ./scripts/install.sh                 # 口令从环境变量 / /etc/default 读取
sudo bash scripts/verify.sh               # 28 项自检
```

## 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/架构说明.md`](docs/架构说明.md) | 六域分层、依赖铁律、事件/端口协作、关键机制、工程纪律 |
| [`docs/模块设计.md`](docs/模块设计.md) | 七业务域职责边界、类清单、设计决策记录 |
| [`docs/接口文档.md`](docs/接口文档.md) | 全部 134 个端点（由 `gen_api_doc.py` 自动提取，可再生成） |
| [`docs/部署与运维手册.md`](docs/部署与运维手册.md) | 安装、升级（增量/全量）、备份恢复、保留策略、故障排查 |
| [`docs/使用说明.md`](docs/使用说明.md) | 页面导览、权限语义、常见操作、数据口径 |
| [`docs/配置项清单.md`](docs/配置项清单.md) | 生产配置项与密钥注入口径 |
| [`docs/踩坑速查.md`](docs/踩坑速查.md) | 按主题整理的事故与坑清单（改代码前必读） |
| [`docs/scripts/README.md`](docs/scripts/README.md) | 运维与验证脚本索引 |

## 已知限制

- **部分系统设置项存表但不生效**：只有 3 个告警阈值被业务读取，其余为展示性配置
- **服务器监控只覆盖本机**，历史曲线存进程内存，重启后清零
- **设备端输出格式建议用 JPEG**：PNG 实测可达 1.2 MB/张，7 天保留期会超出磁盘
- **异机备份未实现**：当前 `OFFSITE_DIR` 指向同一块物理盘，只抗逻辑误删

## 默认账号

| 账号 | 口令 |
|---|---|
| `admin` | `123456`（全新部署库）/ `admin123`（现场机已改） |

> ⚠️ 正式投产前必须修改管理员口令。当前口令记录在
> `/etc/default/camera-detection`（权限 640 root），不入仓库。

## 许可

内部项目，未指定公开许可证。
