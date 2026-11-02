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
| 部署 | Linux（systemd + bash）/ Windows（Inno Setup + PowerShell） | `installer/` |

## 目录结构

```
fact-product/    后端源码（28 个 Controller，含设备端冻结契约 3 端点）
frontend/        前端源码（Vue3 单页应用）
installer/       双平台安装脚本、自检脚本、配置模板
  linux/           systemd 单元 + 9 个 bash 脚本 + build-release.sh
  scripts/         Windows PowerShell 运维脚本（备份/清理/巡检/自检）
  templates/       application.yml 生产模板
docs/            交付文档、架构评审、接口冻结契约
dist-release/    已构建的交付产物（Windows 目录 + Linux tar.gz）
sql/deploy/      四段建库脚本（01 → 02 → 03 → 04 顺序执行）
```

## 核心能力

| 模块 | 说明 |
|---|---|
| **设备接入** | 冻结契约 3 端点：心跳上报、批量检测上报、缺陷图片上传。设备令牌走 `cfg_camera_token` |
| **缺陷映射** | 相机 + 编码 + 产品 + 生效期四维匹配，产品专属优先；未映射返回 422 `UNMAPPED` |
| **统计看板** | 产量/良率/缺陷分布、产线排名、班次对比、部件级分析、30 分钟聚合与日聚合 |
| **主数据配置** | 工厂 → 产线 → 工位 → 相机的层级配置，含依赖校验与 Excel 批量导入 |
| **设备令牌** | 每台相机独立令牌，SHA-256 存储，支持启用/停用与有效期 |
| **异常告警** | 相机故障、计数不符、CPU/内存/温度超阈值五类，入库 + 页面展示 |
| **数据治理** | 定时备份（zstd + SHA-256）、图片清理（先删库记录再删文件）、保留期归档 |
| **服务器监控** | 本机 CPU / 内存 / 分区容量 / 磁盘读写速率与 IOPS 实时指标（OSHI 6.9.3） |

## 快速开始

### 构建

```bash
# 后端
cd fact-product && mvn -B -DskipTests clean package   # target/time-bucket-demo.jar
cd fact-product && mvn -B test                        # 19 个用例

# 前端
cd frontend && npm install && npm run build
```

> ⚠️ **前端产物必须双份同步**：`frontend/dist/*` → `fact-product/src/main/resources/static/`
> （同一份 Vite 产物的两份拷贝，漏了会导致打包出的 JAR 里没有前端页面）

### 建库

按顺序执行四段脚本（**不要用 `dist-release/.../sql/wms-new.sql`**，那缺 15 张 P0 表）：

```bash
sql/deploy/01_init_schema_nodb.sql
sql/deploy/02_final_schema_p0.sql
sql/deploy/03_system_settings.sql
sql/deploy/04_align_entity_columns.sql
```

> ⚠️ `02_final_schema_p0.sql` 会 **DROP 并重建 21 张表**，生产库升级绝不能跑全量流程。

### 部署

```bash
# Linux
tar -xzf camera-detection-system-1.0.0.tar.gz
cd camera-detection-system && sudo ./scripts/install.sh
sudo bash scripts/verify.sh        # 28 项自检

# Windows
# 运行 CameraDetectionSystem-Setup-1.0.0.exe，安装向导选择工作目录
```

## 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/产品介绍.md`](docs/产品介绍.md) | 面向使用者与决策者的产品介绍：功能清单、典型场景、能力边界、配置说明 |
| [`docs/项目说明.md`](docs/项目说明.md) | 详细项目说明与开发实现方案 |
| [`docs/接口冻结契约-v1.0-20260616.md`](docs/api/接口冻结契约-v1.0-20260616.md) | 设备端 3 个冻结端点的完整契约 |
| [`docs/部署方案-Ubuntu22.04.md`](docs/部署方案-Ubuntu22.04.md) | Linux 部署方案 |
| [`docs/部署方案-Windows.md`](docs/部署方案-Windows.md) | Windows 部署方案 |
| [`docs/服务器运行与问题排查手册.md`](docs/服务器运行与问题排查手册.md) | 运维手册：日志体系、存储、备份、故障速查、已修缺陷清单 |
| [`docs/架构评审报告.md`](docs/架构评审报告.md) | 架构、容量、性能评审 |
| [`CODEBUDDY.md`](CODEBUDDY.md) | 面向 AI 助手的仓库导览 |

## 已知限制

- **3 个前端页面使用假数据**：`DetectionList.vue` / `AnomalyList.vue` / `UserList.vue`
  路由可达但列表非真实接口数据
- **部分系统设置项存表但不生效**：只有 3 个告警阈值被业务读取，其余为展示性配置
- **服务器监控只覆盖本机**，历史曲线存进程内存，重启后清零
- **设备端输出格式建议用 JPEG**：PNG 实测可达 1.2 MB/张，7 天保留期会超出磁盘
- **异机备份未实现**：当前 `OFFSITE_DIR` 指向同一块物理盘，只抗逻辑误删

完整清单见 [`docs/产品介绍.md`](docs/产品介绍.md) 第四章「能力边界」。

## 默认账号

| 账号 | 口令 |
|---|---|
| `admin` | `123456`（全新部署库）/ `admin123`（现场机已改） |

> ⚠️ 正式投产前必须修改管理员口令。当前口令明文记录在
> `/etc/default/camera-detection`（权限 640 root）。

## 许可

内部项目，未指定公开许可证。
