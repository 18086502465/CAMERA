# CODEBUDDY.md

This file provides guidance to CodeBuddy Code when working with code in this repository.

## 这个仓库是什么

产线缺陷在线 AI 检测系统：工业相机上报心跳 / 检测计数 / 缺陷图片，后端聚合成产线统计看板，并提供主数据配置、设备令牌、异常告警、运维巡检与服务器资源监控。

单仓双工程 + 部署脚本，**没有多模块 Maven 聚合**：

| 目录 | 内容 |
|---|---|
| `fact-product/` | Spring Boot 2.7.18 / Java 8 单体后端，artifact `time-bucket-demo`，端口 8091 |
| `frontend/` | Vue 3.5 + Vite 5 SPA，dev 端口 3000，代理 `/api` → 8091 |
| `installer/linux/` | 唯一在维护的部署通道（bash + systemd + `verify.sh`） |
| `dist-release/` | 构建产物输出，不入库 |
| `docs/` | 架构 / 模块 / 接口 / 运维 / 使用 / 配置项 / **踩坑速查**；`docs/scripts/` 为运维与验证脚本 |

Windows 版安装脚本（`installer/**/*.ps1`、`.iss`）已冻结，不要在其上继续开发。

## 常用命令

### 后端（在 `fact-product/` 下执行）

```bash
mvn -B -DskipTests clean package     # → target/time-bucket-demo.jar（前端产物已打进 static）
mvn -B test                          # 全量测试，必须全绿；含 ArchUnit 架构守护
mvn -B test -Dtest=ArchitectureTest                    # 单个测试类
mvn -B test -Dtest=DefectImageControllerTest#upload    # 单个方法
```

本地起服务需要两个环境变量（`application.yml` 里是**无默认值**的占位符，缺了会启动失败）：

```bash
MYSQL_PASSWORD=xxx JWT_SECRET=xxx mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8091
```

本机若残留 `SERVER__PORT` 环境变量会覆盖 8091，调试时显式传 `--server.port=8091`。

### 前端（在 `frontend/` 下执行）

```bash
npm ci                 # CI 用 npm ci，本地也建议与 lock 严格一致
npm run dev            # 3000（--host 0.0.0.0），/api 代理到 localhost:8091
npm run build          # vite build + postbuild 自动把产物填进后端 static（仅当 static 为空）
npm run preview        # 预览 dist 产物
```

前端**没有 lint、没有单元测试**（`package.json` 只有 dev/build/preview + postbuild 钩子）。所以 `npm run build` 就是前端唯一的门禁 —— 它挂了才算挂，它过了不代表逻辑对，行为正确性只能靠 `docs/scripts/verify_*_browser.cjs` 浏览器回归。

> 只用 `npm ci`（与 CI 一致）。`npm install` 会在 lock 漂移时装出不同依赖树，
> 导致"本机能构建、CI 也能过、但产物不一致"。已退役的 `sync_static.py` 会删旧 chunk，别再用。
>
> `fact-product/src/main/resources/static/` 已于 2026-11-13 **出库**（见 `.gitignore`）：
> 它是 Vite 产物的副本，140 个文件每次构建全改名，入库只会淹没真实改动。CI 本来就不看它
> （`ci.yml` 第 229 行自己 `rm -rf` 后从 `frontend/dist` 复制）。`postbuild` 只在 static
> **为空**时填充，非空就跳过 —— 覆盖 `index.html` 会让 `build-release.sh` 把新版本误判成上一版，
> 打断历史 chunk 版本链。


### 发版链路

```bash
bash installer/linux/build-release.sh              # 全量包：前端构建 → 同步 static → 打 JAR → 组装 stage → tar.gz
bash installer/linux/build-release.sh --update-only # 轻量增量包（~2MB，只含 BOOT-INF/classes/**）
SUDO_PASSWORD='xxx' bash installer/linux/scripts/deploy_jar.sh /tmp/update-*.zip  # 服务器端：*.zip 增量 / *.jar 全量，失败自动回滚
sudo env VERIFY_PASSWORD='xxx' MYSQL_PASSWORD='xxx' bash installer/linux/scripts/verify.sh
```

`build-release.sh` 是唯一被认可的打包入口 —— 它做的三件事手工很容易漏：清空 `dist/assets`、把前端产物同步进 `static/`、保留历史 chunk 版本。

### 建库

严格按序执行 `fact-product/src/main/resources/sql/deploy/`（**不是** `dist-release` 里的 `wms-new.sql`，那份缺 15 张 P0 表）：

```
01_init_schema_nodb → 02_final_schema_p0 → 03_system_settings → 04_align_entity_columns
```

已有库按需追加增量补丁（幂等，不进全新库流程）：`05_camera_type_migration.sql`（相机类型字段）、
`06_index_tuning.sql`（`camera_images.capture_time` 索引 —— 两处清理任务的判据字段）。

`02_final_schema_p0.sql` 会 **DROP 重建 21 张表**。生产升级绝不能跑全量流程。

### CI（`.github/workflows/ci.yml`）

4 个 job：后端构建+测试（**JDK 8**）、前端 `npm ci` + build、静态检查、产物一致性校验。静态检查拦四类问题，本地改完先自查：**明文口令（数据库/SSH 口令两个固定串，正则见 `ci.yml`）**、**shell 脚本 CRLF 行尾**、**前端残留 `TODO: 替换为真实 API` 假数据**、**Java 9+ API**。产物一致性 job 会解包 JAR 校验 `index.html` 引用的入口 chunk 确实在 `BOOT-INF/classes/static/assets/` 里。

> 后端 job 用 JDK 8 编译，而本机常用 JDK 17。`-source 1.8` **不做 API 白名单检查**，所以 `List.of()` 这类 Java 9+ API 在本机全绿、在 CI 直接 `testCompile` 失败。写测试一律用 Java 8 等价写法（`Collections.emptyList()` / `Collections.singletonList()`）。

## 后端架构

### 七业务域 + 共享内核

```
com.wms
├── TimeBucketApplication      装配根（@MapperScan 七包）
├── init/SchemaInitializer     启动期统一建表（system → defect → analytics），失败记 ERROR 不阻断启动
├── shared/                    common(ResultVO) / util(Excel, ShiftCalculator) / config / web(SpaFallbackController)
├── iam/          登录 JWT、用户、操作日志            (AuthController, SecurityConfig, JwtRequestFilter)
├── masterdata/   工厂/产线/工位/品牌/公司/班次/节假日 + 级联树下发
├── device/       相机档案、心跳、状态快照、服务器指标
├── defect/       缺陷图片上传/检索 ——  现场主通道
├── inspection/   批量上报冻结契约（fact_inspection_* + agg_*）
├── analytics/    产线统计、看板、聚合、告警查询（最重）
└── system/       系统设置、数据清理、Excel 导入导出、映射维护
```

每域分层：`api/`（Controller + DTO/VO）→ `app/`（应用服务、事件、跨域端口）→ `domain/model/`（被动实体）→ `infra/`（`mapper/` MyBatis-Plus + `repository/` JdbcTemplate + `task/`）。层不齐是既有状态：`defect` 没有 `domain/`，`system` 没有 `app/` —— 别为了"整齐"新建空层。

规模参考（2026-11-07 实测）：162 个 Java 文件 / 20,728 行、28 个 Controller / 135 个端点、15 个 Repository / 17 个 Mapper、22 个测试类 / 145 个 `@Test`（展开后 148 个用例）。文档里若见 151 文件 / 134 端点 / 79 用例，都是 DDD 定稿时的旧值。

### ArchUnit 三条铁律（`com.wms.arch.ArchitectureTest`）

1. 跨域只走 `app` 层，业务域不得依赖**其他域**的 `infra`（Repository/Mapper 各域私有）。现存 **13 处**冻结在 `FROZEN_CROSS_DOMAIN`，**只许清零，不许新增**。注意 `ArchitectureTest` 类注释里写的"存量 5 处"是旧值，**以 `FROZEN_CROSS_DOMAIN` 集合里实际行数为准**（改完顺手改注释）。
2. `shared` 不得反向依赖业务域。
3. `domain/model` 不得依赖 `api`/`app`/`infra`。

例外只有两个：`init.SchemaInitializer`（统一建表要跨域调度）和装配根 `TimeBucketApplication`。

### 跨域协作只有两种模式

- **进程内事件**（Spring `ApplicationEvent`，同步）：`device` 发 `CameraAlertEvent`（5 类心跳告警）→ `analytics.CameraAlertEventListener` 落 `fact_anomaly_alert`。监听器吞 `DataAccessException`，不影响心跳主流程。
- **端口-适配器**：接口定义在**消费方**。`device.app.CameraDirectory`（14 方法，供 analytics）← `CameraDirectoryAdapter`；`device.app.SystemSettingReader`（读告警阈值）← `system.infra.SystemSettingReaderAdapter`。

新增跨域调用优先走这两种，不要直接注别的域的 Repository —— 会撞 ArchUnit。

### 两条上报通道（最容易理解错的一点）

| | `POST /api/v1/defect-images/upload` | `POST /api/v1/camera/inspection/batch-upload` |
|---|---|---|
| 地位 | **现场真正在用**，约 1.7 万条（含 TEST 相机压测，每小时清理） | 冻结契约，基本不用 |
| 表 | `defect_image_file/_box/_batch` + `camera_images` | `fact_inspection_*` + `agg_*` |
| 统计后果 | 统计接口应以它为准 | 只读 `agg_*` 的接口现场永远为 0 |

所以统计接口必须做 `defect_image_*` 兜底，响应带 `dataSource` 字段。**判定顺序是"图片事实表优先、聚合表兜底"，不是反过来** —— 反过来会被 `agg_*` 里的孤儿行（已删产线的残留）骗过，出现"筛选后比不筛选还多"。孤儿行由 `purgeOrphanAggregationRows()` 在日聚合重建后清理。

`product_detection_history` 实测 0 行，检测记录页必须走 `defect-images/history`。

### 相机身份：code-first

`camera_code` 是唯一身份（`uk_camera_code`）；`camera_name` 在**现场跨产线重名**（ZK54 与 ZK23 都有 `CV1_Cam1`）。所有层一律按 code 匹配，名称只能作为兜底且**双侧都必须唯一**，多行时拒写 + WARN。前端统一用 `frontend/src/utils/camera-identity.js`，不要在业务组件里自己拿 name 当匹配键。

连带事实：`defect_image_file.camera_id` 里存的其实是 **camera_code**，JOIN 要写 `dim_camera c ON c.camera_code = f.camera_id`。相机↔工位绑定的权威表是 `dim_camera`，`camera_status` 只是运行状态快照。

`camera_type`（`REAL` / `TEST`，默认 `TEST`）决定是否参与定时清理，两张表都有，`dim_camera` 为权威源，保护集合取两表 REAL 的并集。

### 数据保留是两层生命周期

主表 31 天 → 按月 `INSERT…SELECT` 移到 `defect_image_*_hist_YYYYMM`（`DefectImageArchiveTask`，5000 行/批、独立事务）→ 月表 730 天整表 DROP；磁盘文件按 `{yyyyMMdd}` 目录由 cron 删 730 天。查询路由在 `DefectImageRepository.listHistoryPage`：范围全在 31 天内只查主表，否则主表 + 相关月表 `UNION ALL`。

**磁盘文件的生命周期只有一个 owner**：`cleanup-defect-images.sh`（730 天日期目录）。
`DataCleanupTask` 清 `camera_images` 时**只删库记录、不删磁盘文件** —— 该表与 `defect_image_file` 的
`image_path` 100% 重叠（同一批 jpg），Java 侧一旦顺手 `file.delete()`，记录留 730 天而文件 30 天就没了，
必然批量裂图。2026-11-07 已移除该删除循环并加回归测试（`DataCleanupTaskImageTest`），别加回来。

`sys_data_retention_policy` 表代码从不读，改它不生效；真实保留期硬编码在 `DataCleanupTask`（02:00）和 `cleanup-defect-images.sh`（03:30）。

### 认证与权限

| 场景 | 状态码 | reason |
|---|---|---|
| 无 token / 无效 / 过期 | 401 | `AUTHENTICATION_REQUIRED` |
| 已登录但角色不足 | 403 | `ACCESS_DENIED` |

`JwtRequestFilter` 只解析 token、不写 SecurityContext；401/403 由 `SecurityConfig` 显式配置的 entry point / denied handler 分派。白名单必须**精确到方法与路径**，禁止 `/**` 兜底（历史上匿名就能 `PUT /api/v1/system/settings` 改生产库）。只能匿名放行：3 个设备冻结端点（心跳 / 批量上报 / 图片上传，由 service 层用 `cfg_camera_token` 校验）+ 图片静态读取（`<img src>` 不带 Authorization）。新增管理端点必须同改三处：`SecurityConfig` 路径规则、Controller `@PreAuthorize`、`verify.sh` 回归项。

## 前端架构

`axios` 实例在 `utils/request.js`，`baseURL = '/api'`，所以 `api/*.js` 里写的是 `/cameras` 这种相对路径。拦截器只做认证分流，权限判定集中在 `utils/auth-redirect.js`（401 → 清 token 跳登录；403 → 仅未登录才跳，权限不足只透传提示），登录上下文存 sessionStorage `camera_login_redirect`，用后即焚。

路由里有一批**重定向兼容桩**（`/factories`、`/lines`、`/workstations`、`/companies`、`/brands`、`/users`、`/defect-types`、`/holidays` 全部 redirect 到 `/system/config`）。这是既有状态，不代表对应页面可用 —— 这 8 个 `.vue`（`FactoryList`/`LineList`/`WorkstationList`/`CompanyList`/`BrandList`/`UserList`/`DefectTypeList`/`HolidayConfig`）**没有任何路由指向它们**。

2026-11-07 实测口径：`views/` 下 31 个 `.vue` − 16 个路由指向的页面 − 8 个重定向桩 − 1 个子组件（`system/config/ConfigImportDialog.vue` 挂在 `ConfigView` 内）= **6 个真孤立页面**：
`stats/DefectTrend.vue`、`stats/LineDashboard.vue`、`stats/ProductShiftYield.vue`、`stats/StatsOverview.vue`、
`system/OperationLog.vue`、`system/SystemSettings.vue`。它们改代码不会影响任何在线路由，别花时间调。

`vite.config.js` 里 element-plus 的 alias **必须精确匹配到 `node_modules/element-plus/lib/index.js`**（CJS 完整入口），配合 `optimizeDeps.include`；配错则 `ElMessage` / `ElMessageBox` 解析成 `undefined`，构建不报错、运行全站弹窗静默失效（表现为"点删除没反应"）。`elementPlusGuard()` 插件就是把这个坑提前到构建期。

`utils/resource-version.js` 负责部署后旧标签页自愈：Vite chunk 名带内容哈希，每次构建全改名，旧标签页懒加载 404 会让 vue-router 导航**静默中断**（URL 不变、无请求）。它轮询版本并自动刷新一次；`build-release.sh` 另外保留最近 3 个版本 chunk（`KEEP_STATIC_VERSIONS`）。

## 改动落点速查

| 要改什么 | 往哪写 |
|---|---|
| 新增/修改 HTTP 端点 | `com.wms.<域>/api/*Controller.java` + `@PreAuthorize`；同时在 `SecurityConfig` 与 `installer/linux/scripts/verify.sh` 补回归项 |
| 新增配置项 | **两份 `application.yml` 同时改**（`fact-product/src/main/resources/` + `installer/templates/`），改完跑 `python3 installer/linux/scripts/check_config_parity.py`。Spring 外部配置按 key 合并，漏改一边 = 现场改了不生效且两边都不报错 |
| 新增/改计划任务 | 改 `install.sh` 的**生成模板**还不够 —— 已经在跑的那台机器上 `/etc/cron.d/*` 不会自动更新，必须同时重写现场文件，否则跑的还是旧副本（现场踩着过：磁盘巡检一直监控的是系统盘） |
| 新增查询/写入 SQL | `com.wms.<域>/infra/repository/`（表属哪个域就放哪个域，跟调用方无关） |
| 新增 MyBatis 查询 | `com.wms.<域>/infra/mapper/` + `resources/mapper/*.xml`（XML 优先于 `@Select`，id 不得与 BaseMapper 方法重名） |
| 跨域取数 | 消费方定义端口接口 → 提供方 `app/` 下写 Adapter；或走 `ApplicationEvent` |
| 定时任务 | `infra/task/`，cron 用 `${app.xxx.cron:默认值}` 形式，别硬编码 |
| 前端新页面 | `frontend/src/views/` + `router/index.js` + `api/*.js`；日期/相机身份一律用 `utils/` 下的公共函数 |
| 表结构变更 | `sql/deploy/` 新增 `0X_*.sql`（不改已发布的 01~04），并在对应域 Repository 的 `ensureXxx`（`ensureColumn` / `ensureSchemaFull`）里做幂等补列/补索引 —— `init/SchemaInitializer` 启动期统一调度，失败只记 ERROR 不阻断启动 |

## 改代码前必须知道的纪律

- **SQL 逐字搬运**：重构 / 搬包时 SQL 文本与参数拼接方式原样保留。但"文本一致"不等于"SQL 正确" —— 搬迁曾忠实保留 `w.station_id`（实为 `w.workstation_id`）的既有 bug，文本比对抓不到，要用 `docs/scripts/check_sql_columns.py`（information_schema 反查）静态校验列名。
- **异常语义不要顺手优化**：`countOrZero` 吞异常返回 0、`ensureColumn` 只记 WARN、`insertAnomalyAlert` 只 catch `DataAccessException`、`line_id` 空写 0 而非 NULL —— 都是有意的。
- **写操作超过 5 个参数用 DTO**（如 19 列的 `CameraDefectMappingRow`），否则位置参数错位。
- **SQL 按表所属域下沉**，不是按调用方分置：device 的 Service 查 `sys_system_setting`，SQL 仍放 system 域 Repository。
- **前端产物不双份入库**：`static/` 已出库，`frontend/dist` 才是唯一产物源。需要手工同步时用 `sync_static_keep_legacy.py`（**只补不删**）；`sync_static.py` 会删旧 chunk，正是"部署后旧页面点不动"的根因。别整目录 `rm -rf static`。打包走 `build-release.sh`，它独占版本链逻辑。
- **构建前清空 `dist/assets`**：`vite build` 默认不清理输出目录，会混入上次的旧 chunk。
- **shell / sql / py 一律 LF**（`.gitattributes` 强制）。CRLF 传到 Linux 会报 `set: pipefail: invalid option name`，整条部署链直接失败。
- **配置与密钥分离**：唯一密钥源 `/etc/default/camera-detection`（640 root），systemd `EnvironmentFile` 注入。占位符写成 `${MYSQL_PASSWORD}`，**绝不能带空默认值** —— 带空默认值是"服务 active 但业务全错"，无默认值才会启动即报 `Could not resolve placeholder`。生产还须显式覆盖 `mybatis-plus.configuration.log-impl=Slf4jImpl` 与 `logging.level.com.wms.mapper=INFO`。
- **测试数据只用 `ZZTEST*` 前缀**，验证后清理；现场（杭州临平二期）数据一律不得动。
- **磁盘图片文件只有一个删除者**：`cleanup-defect-images.sh`（730 天日期目录）。`DataCleanupTask` 清 `camera_images` **只删行、不删文件** —— 两表 `image_path` 完全重叠，Java 侧删文件会让「记录留 730 天、文件 30 天消失」直接裂图。测试相机清理脚本另有三道防误删防线（双表 OR 保护集 / 保护集为空即中止 / 单轮超阈值即中止），改脚本时必须保留。
- **脚本的「先删库后删盘」只适用于 shell 侧同类清理**：`cleanup-defect-images.sh` 删库记录 → 删磁盘文件；反过来会留「库有行、盘无文件」的裂图。Java 侧不再有这个顺序问题（不碰文件）。
- **同名脚本只有一份**：`installer/linux/scripts/` 是唯一真源，`docs/scripts/` **不留任何副本**。
  2026-11-12 删除了 `jar_manifest.py`、`prune_legacy_chunks.py`、`recover_legacy_chunks.py` 三份
  与 installer 逐字节一致的副本 —— 本仓库栽过「副本漂移、测的是旧版」，而增量部署正确性
  直接依赖 `jar_manifest.py`。**不要再往 `docs/scripts/` 拷第二份。**

## 验证顺序（每轮改动照此走）

`mvn test` → 打包 → 部署 → `verify.sh` 全绿 → 接口快照键结构 diff（`api_snapshot.py` + `compare_structure.py`，比键路径不比值）→ 写接口业务回归 → 浏览器回归（`docs/scripts/verify_*_browser.cjs`，登录必须用 `browser-helper.cjs`）→ 清理测试数据。

碰到对应区域时额外跑回归脚本，别靠肉眼：

| 改动区域 | 回归脚本 |
|---|---|
| 清理脚本 / `camera_type` 判定 | `docs/scripts/test_cleanup_protect_set.sh`、`verify_camera_type_guards.sh` |
| 前端 API 路径 | `docs/scripts/probe_api_paths.sh`（防 `/users` vs `/api/user` 这类断裂） |
| SQL 列名 | `docs/scripts/check_sql_columns.py`（information_schema 反查） |
| 老 chunk / 旧标签页 | `installer/linux/scripts/recover_legacy_chunks.py`、`prune_legacy_chunks.py` |

`verify.sh` 必须 **root + 正确 `VERIFY_PASSWORD`**，否则会有一批项误报失败。

## 文档地图

完整索引与「什么问题查哪份」见 `docs/README.md`。这里只列改代码时**必看**的三份：

| 文档 | 什么时候看 |
|---|---|
| `docs/踩坑速查.md` | **改任何东西之前**；13 节真实事故与根因 |
| `docs/架构说明.md` | 分层规则、依赖铁律、关键机制（本文件 §后端架构 的精简版来源） |
| `docs/部署与运维手册.md` | 安装、增量/全量升级、备份恢复、故障排查 |

其余：`docs/模块设计.md`（每域类清单）、`docs/接口文档.md`（端点清单，`gen_api_doc.py` 生成勿手改）、
`docs/配置项清单.md`、`docs/使用说明.md`、`docs/scripts/README.md`（脚本索引）。

**`docs/archive/` 下的任何文档都不要拿来做依据** —— 那是历史评审过程记录，结论多已被推翻，其中的数字不可信。
