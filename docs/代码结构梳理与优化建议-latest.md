# 产线缺陷在线AI检测系统 — 代码结构梳理与优化建议

版本：v1.0  
日期：2026-06-16  
分析对象：`D:\45344\0004-软件源码`  
分析范围：后端 `fact-product`、前端 `frontend`、构建发布脚本、安装交付目录、SQL 与测试报告目录  
说明：本文基于当前本地代码目录扫描结果整理，不修改业务代码。

---

## 1. 执行摘要

当前项目已经具备本地化交付能力，包含 Spring Boot 后端、Vue 前端、MySQL 初始化脚本、Windows 安装包脚本、测试报告与交付文档。功能上覆盖相机状态、图像记录、产线统计、系统配置、用户权限、操作日志、安装部署等模块。

从代码结构看，项目仍保留明显的“快速迭代交付型”特征：

- 后端以 `controller + service + mapper/entity` 的技术分层为主，业务域边界不够清晰。
- 多个 Controller 和 Service 文件过大，承载了接口、业务编排、SQL 查询、导入导出、统计聚合、初始化逻辑等多种职责。
- 前端多个页面级 Vue 单文件组件过大，页面、表单、弹窗、图表、接口调用、状态管理和样式混在一个文件内。
- 构建部署已基本打通，但仍存在脚本路径硬编码、SQL 版本多份并存、交付目录规范可继续优化的问题。

建议后续采用渐进式重构策略：

> **短期不建议一次性大拆大改。建议先冻结接口协议与核心页面交互，再按“拆 Controller、沉淀 Service、归拢 SQL、抽前端组件、补自动化测试”的节奏分阶段优化。**

---

## 2. 项目总体目录结构

当前根目录：`D:\45344\0004-软件源码`

| 目录 / 文件 | 当前作用 | 结构评价 |
|---|---|---|
| `fact-product` | Spring Boot 后端源码 | 核心后端工程，包结构以技术层分组，业务模块边界需要增强 |
| `frontend` | Vue 3 + Vite 前端源码 | 核心前端工程，页面功能较完整，但页面组件偏大 |
| `dist-release` | 打包后的发布目录 | 已形成交付产物目录，建议进一步标准化版本与文档归档 |
| `installer` | 安装包相关脚本或资源 | 应继续沉淀安装包模板、服务注册、卸载、配置初始化逻辑 |
| `scripts` | 数据库、测试、运维脚本 | 建议统一命名规范，区分 db/test/release/ops |
| `docs` | 项目文档 | 建议作为正式交付文档和研发文档根目录 |
| `test-reports` | 测试报告 | 建议保留人工/自动化测试报告，同时接入 CI 测试输出 |
| `test-artifacts` | 测试过程产物 | 建议定期清理，避免混入发布包 |
| `logs` | 本地运行日志 | 发布时应改为安装目录外可配置路径 |
| `build-release.ps1` | 一键构建脚本 | 已具备发布自动化基础，建议拆分为前端构建、后端构建、安装包构建三个阶段 |
| `wms-new-*.sql` | MySQL 数据库脚本 | 版本较多，建议迁移到 `db/migrations` 或 `release/sql` 规范管理 |

---

## 3. 后端代码结构现状

后端工程路径：`D:\45344\0004-软件源码\fact-product`

### 3.1 技术栈

根据 `pom.xml` 当前配置：

| 类型 | 当前技术 |
|---|---|
| Java 版本 | Java 8 |
| Web 框架 | Spring Boot `2.7.15` |
| ORM / DAO | MyBatis Plus `3.5.3.1` + 部分 `JdbcTemplate` |
| 数据库 | MySQL 8.0 |
| 连接池 | Druid `1.2.16` |
| 安全 | Spring Security + JWT |
| 文档/文件处理 | Apache POI |
| JSON | FastJSON `1.2.83`，同时使用 Jackson JWT 模块 |
| 调度 | Spring Scheduling |
| AOP | Spring AOP |

注意：后端 `artifactId` 和打包名仍为 `time-bucket-demo`，与当前产品名“产线缺陷在线AI检测系统”不一致，建议在发布稳定后统一调整。

### 3.2 当前 Java 包结构

Java 根包：`D:\45344\0004-软件源码\fact-product\src\main\java\com\wms`

| 包名 | Java 文件数 | 当前职责 |
|---|---:|---|
| `aspect` | 1 | 操作日志、切面类 |
| `config` | 3 | 安全、Web、跨域、静态资源等配置 |
| `controller` | 27 | API 控制器，当前承载大量业务逻辑 |
| `dto` | 10 | 请求/响应数据对象，数量偏少 |
| `entity` | 17 | 数据库实体 |
| `mapper` | 17 | MyBatis Plus Mapper |
| `service` | 37 | 业务服务与实现类 |
| `task` | 3 | 定时任务、聚合任务 |
| `utils` | 5 | Excel、Token、文件等工具类 |
| `vo` | 2 | 视图对象，数量偏少 |

### 3.3 Controller 与接口规模

当前 `controller` 包有 27 个 Controller，扫描到约 157 个 Spring Mapping 注解。

主要 Controller 包括：

| Controller | 当前职责观察 |
|---|---|
| `AuthController` | 登录、注册、鉴权相关 |
| `CameraStatusHeartbeatController` | 相机心跳状态上报 |
| `BatchUploadController` | 相机检测批量上报 |
| `DefectImageController` | 缺陷图片上传 |
| `CameraDashboardController` | 相机状态看板聚合接口 |
| `StatsController` | 图像记录、统计相关接口，文件较大 |
| `LineAnalyticsController` | 产线统计、动态报表、聚合、图表配置等，文件最大 |
| `FinalConfigController` | 产品、缺陷、映射等最终配置接口 |
| `ConfigImportController` | 配置导入导出 |
| `SystemSettingsController` | 系统设置，包含部分初始化和数据库结构处理逻辑 |
| `SysOperationLogController` | 操作日志查询、删除、清理 |
| `DimFactoryController` / `DimProductionLineController` / `DimWorkstationController` | 工厂、产线、工位维度配置 |

### 3.4 后端大文件分布

| 文件 | 大小 byte | 主要风险 |
|---|---:|---|
| `controller\LineAnalyticsController.java` | 68353 | 产线统计相关职责过度集中 |
| `controller\StatsController.java` | 47544 | 图像记录、统计查询逻辑偏重 |
| `service\impl\DefectImageServiceImpl.java` | 39421 | 图片接收、校验、解析、保存、数据库写入、兼容逻辑混合 |
| `service\impl\inspection\InspectionUploadServiceImpl.java` | 31596 | 检测上报处理链条较长，建议拆分校验、映射、写入、聚合 |
| `controller\FinalConfigController.java` | 27768 | 配置类接口聚合过多 |
| `controller\ConfigImportController.java` | 27197 | 导入模板、校验、写库、结果组装逻辑偏重 |
| `service\impl\CameraStatusServiceImpl.java` | 19076 | 相机状态与统计聚合逻辑可继续拆分 |
| `controller\SystemSettingsController.java` | 19022 | 系统设置与数据库初始化职责混合 |
| `controller\CameraDashboardController.java` | 13881 | 相机状态页面聚合逻辑偏重 |
| `utils\ExcelUtils.java` | 10611 | Excel 工具类偏大，可按模板/导入/导出拆分 |
| `controller\SysOperationLogController.java` | 10352 | 日志查询、删除、清理逻辑需要服务层沉淀 |

### 3.5 SQL 与数据访问现状

扫描 `JdbcTemplate`、`SELECT`、`INSERT INTO`、`UPDATE`、`DELETE FROM` 等关键字，命中约 281 处。

当前问题不是“不能使用手写 SQL”，而是：

- SQL 分散在 Controller 和 Service 中，后续字段调整影响面不清晰。
- 统计类查询、配置类 CRUD、导入类写入、初始化类建表逻辑混在一起。
- 部分 Controller 直接拼装 SQL 或直接调用 `JdbcTemplate`，破坏分层边界。
- 动态报表和聚合查询如果继续增长，SQL 维护成本会快速上升。

建议后续将 SQL 按业务域归拢到：

- `mapper`：常规实体 CRUD。
- `repository` 或 `dao`：复杂统计、动态报表、聚合查询。
- `migration` 或 SQL 脚本：建表、字段升级、初始化数据。

---

## 4. 后端代码结构主要问题

### 4.1 Controller 过胖

当前多个 Controller 不只是做 HTTP 入参出参转换，还包含：

- 参数校验；
- 业务规则判断；
- SQL 查询和写入；
- 聚合统计；
- Excel 导入导出；
- 数据库结构初始化；
- 任务触发；
- 页面字段适配。

这会带来几个风险：

1. 单个接口修改容易影响其他接口。
2. 业务逻辑难以复用。
3. 单元测试困难。
4. 新人理解成本高。
5. 接口层和业务层边界不清晰。

建议目标：Controller 只保留协议转换和调用应用服务。

```java
@RestController
@RequestMapping("/api/v1/camera/status")
public class CameraStatusHeartbeatController {
    private final CameraStatusAppService appService;

    @PostMapping("/heartbeat")
    public ApiResult<?> heartbeat(@RequestBody CameraHeartbeatRequest request) {
        return ApiResult.ok(appService.handleHeartbeat(request));
    }
}
```

业务细节下沉到 `CameraStatusAppService`、`CameraStatusDomainService`、`CameraStatusRepository`。

### 4.2 业务域没有模块化

当前是典型技术分层：

```text
controller
service
mapper
entity
dto
vo
```

当业务模块变多后，相机状态、缺陷图片、检测上报、产线统计、系统配置、用户权限、操作日志会互相交叉，文件之间依赖关系不直观。

建议逐步改为“业务模块 + 层次”的组合结构。

### 4.3 DTO / VO / Entity 边界不够清晰

当前 `dto` 和 `vo` 数量偏少，相比 157 个接口，说明很多接口可能直接使用 Map、Entity 或临时结构返回。

建议：

- 入参统一使用 `Request DTO`。
- 出参统一使用 `Response DTO / VO`。
- 数据库实体 `Entity` 不直接暴露给前端。
- 导入导出使用独立 `ExcelRow DTO`。
- 外部设备上报协议使用独立 `Integration DTO`，避免和页面 DTO 混用。

### 4.4 初始化建表逻辑混入业务代码

当前 `SystemSettingsController`、`DefectImageServiceImpl` 等位置存在数据库结构初始化、字段补齐或兼容逻辑倾向。

建议：

- P0：将初始化 SQL 迁移到 `scripts/db/init` 和 `scripts/db/upgrade`。
- P1：引入 Flyway 或 Liquibase。
- P2：建立数据库版本表，安装包启动时检测版本并执行增量升级。

### 4.5 新旧接口和兼容逻辑未隔离

当前系统经历多轮产品迭代，存在旧页面、旧接口、旧字段、兼容路径。继续混在主流程中会造成：

- 新功能不敢删除旧字段；
- 接口返回越来越臃肿；
- 页面适配逻辑变多；
- 测试用例难以确认真实口径。

建议：

- 明确 `/api/v1` 为当前稳定协议。
- 旧接口统一标记 deprecated。
- 对外设备上报接口单独放入 `integration` 模块。
- 页面查询接口单独放入 `query` 或 `dashboard` 模块。

### 4.6 安全与权限逻辑需要集中

当前前端已有页面级 `requiresAdmin` 路由控制，后端也使用 Spring Security。建议继续强化：

- 后端必须作为最终权限判定点。
- 用户管理、配置删除、日志清理、导入导出等危险操作增加权限注解。
- 设备上报 Token 和用户登录 Token 建议分离。
- 操作日志切面应覆盖增删改、导入、导出、清理、聚合任务执行等动作。

### 4.7 项目命名仍有历史包袱

观察到：

- Maven `artifactId` 为 `time-bucket-demo`。
- 包名仍为 `com.wms`。
- 数据库名为 `wms-new`。

建议发布稳定后统一命名，例如：

| 项 | 建议 |
|---|---|
| Maven artifactId | `camera-detection-system` 或 `production-defect-ai-system` |
| finalName | `camera-detection-system` |
| Java 根包 | 可继续保留 `com.wms`，如需大版本升级再改 |
| 数据库 | 现阶段可保留 `wms-new`，避免影响安装脚本 |
| 服务名 | `CameraDetectionSystem` |

---

## 5. 后端建议目标结构

建议优先采用“模块化单体”结构，不建议立即拆微服务。

```text
com.wms
├─ common
│  ├─ api                 # ApiResult、分页对象、错误码
│  ├─ exception            # 全局异常、业务异常
│  ├─ pagination           # 分页模型
│  ├─ file                 # 文件保存、校验、清理
│  └─ util                 # 通用工具
├─ security
│  ├─ auth                 # 登录、JWT、用户上下文
│  └─ device               # 设备 Token / 签名校验
├─ modules
│  ├─ camera
│  │  ├─ api               # 相机状态/配置 Controller
│  │  ├─ application       # 应用服务：状态上报、状态查询
│  │  ├─ domain            # 状态枚举、离线规则、告警规则
│  │  ├─ infrastructure    # mapper/repository
│  │  └─ dto
│  ├─ image
│  │  ├─ api               # 缺陷图片上传/查询
│  │  ├─ application       # 上传处理、解析、预览查询
│  │  ├─ domain            # 图片批次、缺陷框、文件规则
│  │  ├─ infrastructure
│  │  └─ dto
│  ├─ inspection
│  │  ├─ api               # 检测统计批量上报
│  │  ├─ application       # 校验、映射、入库、触发聚合
│  │  ├─ domain            # 缺陷编码、增量计数、映射规则
│  │  ├─ infrastructure
│  │  └─ dto
│  ├─ analytics
│  │  ├─ api               # 产线统计、图表、看板模板
│  │  ├─ application       # 查询编排、聚合任务
│  │  ├─ domain            # 图表配置、维度、指标、聚合口径
│  │  ├─ infrastructure    # 统计 SQL repository
│  │  └─ dto
│  ├─ systemconfig
│  │  ├─ api               # 工厂、产线、工位、相机、产品、缺陷、映射
│  │  ├─ application       # 配置 CRUD、导入导出、依赖校验
│  │  ├─ domain            # 层级关系、删除规则、导入策略
│  │  ├─ infrastructure
│  │  └─ dto
│  ├─ operationlog
│  └─ user
├─ task
│  ├─ aggregation          # 日聚合、补偿任务
│  └─ cleanup              # 图片、日志、历史数据清理
└─ migration               # 如引入 Flyway/Liquibase，可放迁移配置
```

### 5.1 推荐拆分方向

| 当前文件 | 建议拆分为 |
|---|---|
| `LineAnalyticsController` | `ChartConfigController`、`DashboardTemplateController`、`AnalyticsQueryController`、`AggregationTaskController`、`AnalyticsExportController` |
| `StatsController` | `ImageRecordController`、`StatsSummaryController`、`ImagePreviewController` |
| `FinalConfigController` | `ProductConfigController`、`DefectTypeConfigController`、`DefectMappingController` |
| `ConfigImportController` | `ConfigTemplateController`、`ConfigImportController`、`ConfigExportController`、`ImportValidationService` |
| `DefectImageServiceImpl` | `ImageUploadValidator`、`ImageStorageService`、`DefectMetadataParser`、`DefectImageRepository`、`ImageRecordQueryService` |
| `InspectionUploadServiceImpl` | `InspectionProtocolValidator`、`DefectCodeMappingService`、`InspectionRecordWriter`、`InspectionAggregationTrigger` |

---

## 6. 前端代码结构现状

前端工程路径：`D:\45344\0004-软件源码\frontend`

### 6.1 技术栈

根据 `package.json`：

| 类型 | 当前技术 |
|---|---|
| 框架 | Vue `3.5.34` |
| 构建工具 | Vite `5.2.0` |
| 路由 | Vue Router `4.6.4` |
| 状态管理 | Pinia `3.0.4` |
| UI 组件库 | Element Plus `2.7.0` |
| 图表 | ECharts `5.6.0` |
| HTTP | Axios `1.16.1` |

当前 `package.json` 的 `scripts` 硬编码了本机 Node 路径：

```json
"dev": "\"C:\\Users\\Administrator\\AppData\\Local\\Programs\\nodejs\\current\\node.exe\" ./node_modules/vite/bin/vite.js --host 0.0.0.0"
```

建议改为标准可移植写法：

```json
"dev": "vite --host 0.0.0.0",
"build": "vite build",
"preview": "vite preview"
```

### 6.2 当前前端源码结构

源码路径：`D:\45344\0004-软件源码\frontend\src`

| 目录 | Vue 文件数 | JS 文件数 | 当前职责 |
|---|---:|---:|---|
| `api` | 0 | 18 | 按业务对象封装 Axios 接口 |
| `components` | 7 | 0 | Layout、相机记录、图表、小组件 |
| `router` | 0 | 1 | 路由配置与页面权限守卫 |
| `stores` | 0 | 1 | Pinia 用户状态 |
| `styles` | 0 | 0 | 全局样式文件 |
| `utils` | 0 | 3 | Axios request、auth 等工具 |
| `views` | 29 | 0 | 页面级组件，业务逻辑集中 |

### 6.3 当前 API 模块

`src/api` 下有 18 个 JS 文件：

| API 文件 | 说明 |
|---|---|
| `analytics.js` | 产线统计、图表、看板配置等 |
| `stats.js` | 统计、图像记录等 |
| `camera.js` | 相机相关 |
| `final-config.js` | 产品、缺陷、映射等最终配置 |
| `operation-log.js` | 操作日志 |
| `system-settings.js` | 系统设置 |
| `factory.js` / `line.js` / `workstation.js` / `company.js` | 基础配置维度 |
| `defect-type.js` / `defect.js` / `brand.js` | 缺陷、产品品牌相关 |
| `auth.js` / `user.js` | 登录和用户管理 |

### 6.4 前端大文件分布

| 文件 | 大小 byte | 主要风险 |
|---|---:|---|
| `views\stats\LineAnalyticsView.vue` | 85933 | 动态看板、图表配置、筛选、聚合任务、导出等全部集中 |
| `views\system\ConfigView.vue` | 73083 | 系统配置多个 Tab 和大量 CRUD 表单集中 |
| `views\camera\CameraStatusView.vue` | 30387 | 相机上传、状态表格、筛选、异常优先等逻辑集中 |
| `components\CameraImageRecords.vue` | 30095 | 图像记录列表、展开、分页、预览弹窗、缺陷框展示集中 |
| `views\Dashboard.vue` | 19101 | 旧看板页面逻辑仍保留 |
| `views\system\OperationLog.vue` | 15950 | 操作日志独立页逻辑偏重 |
| `styles\global.css` | 11691 | 全局样式较多，需沉淀设计变量和组件级规范 |

---

## 7. 前端代码结构主要问题

### 7.1 页面级 SFC 过大

`LineAnalyticsView.vue` 和 `ConfigView.vue` 已超过 70KB，说明一个文件内包含大量状态、接口、弹窗、表格、表单和样式。

这会导致：

- 修改一个小交互需要理解整个页面；
- 弹窗和表格逻辑难复用；
- 测试定位困难；
- 样式互相影响；
- 后续多人协作容易冲突。

### 7.2 缺少 composables / hooks 分层

当前页面内存在大量可抽离逻辑：

- 分页；
- 查询条件；
- 表格加载；
- 导入导出；
- 删除二次确认；
- 后端异常提示；
- 图表配置预览；
- 图片预览上一张/下一张；
- 系统配置层级选择。

建议抽到 `composables` 或模块内 `hooks`。

### 7.3 通用状态组件需要统一入口

项目已有 `EmptyState.vue`，但加载、异常、无权限、空状态、保存中、导入中等状态建议进一步统一：

- `BaseEmpty`；
- `BaseError`；
- `BaseLoading`；
- `BasePageHeader`；
- `BaseToolbar`；
- `BasePagination`；
- `BaseConfirmDelete`。

### 7.4 系统配置页面需要模块拆分

系统配置现在承载：

- 工厂；
- 产线；
- 工位；
- 相机；
- 产品；
- 缺陷类型；
- 缺陷映射；
- 用户；
- 操作日志；
- 导入导出；
- 系统设置。

建议拆为容器页 + 子模块：

```text
views/system/config
├─ ConfigShell.vue                 # Tab、顶部布局、权限控制
├─ hierarchy
│  ├─ HierarchyTree.vue             # 工厂-产线-工位-相机层级树
│  ├─ FactoryPanel.vue
│  ├─ LinePanel.vue
│  ├─ WorkstationPanel.vue
│  └─ CameraPanel.vue
├─ product
│  └─ ProductPanel.vue
├─ defect
│  ├─ DefectTypePanel.vue
│  └─ DefectMappingPanel.vue
├─ user
│  └─ UserPanel.vue
├─ operation-log
│  └─ OperationLogPanel.vue
└─ import-export
   ├─ ConfigImportDialog.vue
   └─ ConfigExportDialog.vue
```

### 7.5 产线统计页面需要组件化

`LineAnalyticsView.vue` 建议拆为：

```text
views/stats/line-analytics
├─ LineAnalyticsView.vue            # 页面壳，只负责组合
├─ components
│  ├─ AnalyticsFilterBar.vue         # 全局筛选
│  ├─ DashboardToolbar.vue           # 新增图表、模板、导出、最大化
│  ├─ ChartGrid.vue                  # 拖拽/布局容器
│  ├─ ChartCard.vue                  # 单图表卡片，编辑/删除/导出
│  ├─ ChartConfigDialog.vue          # 图表配置弹窗
│  ├─ ChartPreview.vue               # 实时预览
│  ├─ MetricSelector.vue             # 指标选择
│  ├─ DimensionSelector.vue          # 维度选择
│  ├─ TemplateManagerDialog.vue      # 看板模板
│  └─ AggregationTaskDialog.vue      # 聚合任务
├─ composables
│  ├─ useAnalyticsFilters.js
│  ├─ useChartConfig.js
│  ├─ useDashboardTemplate.js
│  └─ useAggregationTask.js
└─ chart-renderers
   ├─ LineChartRenderer.vue
   ├─ BarChartRenderer.vue
   ├─ StackChartRenderer.vue
   └─ PieChartRenderer.vue
```

### 7.6 图像记录组件需要拆分

`CameraImageRecords.vue` 建议拆为：

```text
components/image-records
├─ CameraImageRecords.vue           # 容器
├─ ImageRecordSearchBar.vue         # 搜索条件
├─ CameraImageGroup.vue             # 单个相机一行展示
├─ ImageRecordTable.vue             # 明细表格
├─ ImagePreviewDialog.vue           # 图片预览 + 左右切换
├─ DefectBoxOverlay.vue             # 缺陷框渲染
└─ useImageRecords.js               # 分页、展开、预览逻辑
```

### 7.7 路由配置存在编码显示异常

扫描 `src/router/index.js` 时，部分中文 `meta.title` 和注释呈现乱码。这可能是文件编码、历史保存方式或终端显示问题导致。

建议：

- 全项目统一 UTF-8 编码。
- 对路由标题、菜单文案、按钮文案做一次编码检查。
- 建议后续引入 `constants/menu.js` 或 `i18n`，避免路由中堆积大量中文文案。

### 7.8 API 层缺少统一响应模型

当前 `src/api/*.js` 按业务拆分是好的，但建议统一：

- 请求参数命名；
- 分页参数：`page`、`pageSize`；
- 响应结构：`code`、`message`、`data`；
- 错误处理：422 参数问题、401 未登录、403 无权限、500 服务异常；
- 文件上传错误提示：图片数量超过、文件名不匹配、metadata 缺失、图片解析失败等。

---

## 8. 前端建议目标结构

考虑当前项目规模，建议先采用“业务模块目录 + shared 公共能力”的方式，而不是立即引入过重的架构范式。

```text
src
├─ app
│  ├─ App.vue
│  ├─ router
│  ├─ store
│  └─ providers
├─ shared
│  ├─ api                      # request、统一错误处理、分页协议
│  ├─ components               # BaseTable、BaseDialog、BaseEmpty、BasePagination
│  ├─ constants                # 状态枚举、权限枚举、菜单定义
│  ├─ styles                   # 设计变量、全局样式、Element Plus 覆盖
│  └─ utils
├─ modules
│  ├─ auth
│  ├─ camera-status
│  ├─ image-records
│  ├─ line-analytics
│  ├─ system-config
│  ├─ operation-log
│  └─ user
├─ components                  # 过渡期保留，逐步迁入 shared/modules
└─ views                       # 过渡期保留路由页面壳
```

### 8.1 分阶段迁移原则

1. 先抽公共能力，不改接口行为。
2. 再拆页面组件，不改视觉表现。
3. 最后调整目录结构，不同时做业务重构。
4. 每次拆分后执行页面级回归。

---

## 9. 构建、发布与部署结构问题

### 9.1 当前优点

- 已有 `build-release.ps1`，说明项目具备一键构建意识。
- 已有 `dist-release`，说明产物交付目录已经形成。
- 已有 `installer`，说明 Windows 安装包是明确交付目标。
- 已有测试报告目录，说明项目已进入验收闭环阶段。

### 9.2 当前风险

| 问题 | 影响 | 建议 |
|---|---|---|
| 前端 `package.json` 硬编码 Node 路径 | 换机器后构建失败 | 改为标准 `vite` 命令，由环境变量或脚本检查 Node |
| SQL 文件多版本并存 | 初始化时容易选错脚本 | 建立 `db/init.sql`、`db/migration/Vxxx.sql` 规范 |
| 发布目录和测试产物混合 | 安装包可能夹带无关文件 | `dist-release` 只放发布产物，测试产物进 `test-artifacts` |
| 文档分散 | 交付和研发难统一 | `docs/product`、`docs/api`、`docs/deploy`、`docs/dev` 分类 |
| 安装配置和运行配置边界需清晰 | 新电脑部署易出错 | 外部配置目录固定，如 `D:/CameraDetectionSystem/config/application.yml` |

### 9.3 建议交付目录规范

```text
CameraDetectionSystem
├─ app
│  ├─ camera-detection-system.jar
│  └─ frontend-dist/                # 如仍需保留静态资源备份
├─ config
│  └─ application.yml
├─ data
│  ├─ uploads
│  └─ export
├─ logs
├─ db
│  ├─ init.sql
│  └─ migrations
├─ scripts
│  ├─ install-service.ps1
│  ├─ uninstall-service.ps1
│  ├─ start-service.ps1
│  ├─ stop-service.ps1
│  ├─ init-db.ps1
│  └─ backup-db.ps1
├─ docs
└─ runtime
   └─ jre/                          # 如安装包内置 JRE
```

---

## 10. 测试结构建议

当前项目有测试报告和测试脚本产物，但建议继续纳入标准工程结构。

### 10.1 后端测试建议

```text
fact-product/src/test/java/com/wms
├─ modules
│  ├─ camera
│  ├─ image
│  ├─ inspection
│  ├─ analytics
│  └─ systemconfig
├─ integration
└─ contract
```

建议补充：

- 设备上报接口契约测试；
- 图片上传边界测试：0 张、1 张、100 张、101 张、metadata 不匹配；
- 缺陷映射 422 测试；
- 系统配置删除依赖校验测试；
- 操作日志删除/清空测试；
- 产线统计图表保存、编辑、删除测试；
- 日聚合任务测试。

### 10.2 前端测试建议

建议引入：

- Vitest：组件和工具函数测试；
- Playwright：端到端页面测试；
- Mock Service Worker 或本地测试 API：模拟后端异常和边界响应。

重点覆盖：

- 登录与权限跳转；
- 主 Tab 默认页；
- 全局分页；
- 空状态、异常状态、加载状态；
- 相机状态筛选、排序、状态展示；
- 图像记录搜索、展开、分页、预览左右切换；
- 产线统计图表新增、编辑、删除、保存、刷新；
- 系统配置 CRUD、导入导出、依赖删除拦截。

---

## 11. 分阶段优化计划

### P0：低风险结构治理，优先保障可维护和可交付

建议周期：1～2 周。

| 优化项 | 说明 | 风险 |
|---|---|---|
| 修复前端构建脚本硬编码路径 | 改 `package.json` scripts，提升可移植性 | 低 |
| 建立统一分页、空状态、错误状态组件 | 减少页面重复代码 | 低 |
| 抽取系统配置删除确认和依赖校验前端组件 | 统一危险操作体验 | 低 |
| 拆分 `CameraImageRecords.vue` | 优先拆搜索、相机组、预览弹窗 | 中低 |
| 拆分 `CameraStatusView.vue` | 抽筛选栏、状态表格、上传表格 | 中低 |
| 给大 Controller 增加 facade service | Controller 先瘦身，不改接口 | 中 |
| 统一 API 错误提示 | 特别是 400/401/403/422/500 | 低 |
| 梳理 SQL 文件版本 | 明确当前安装包使用哪个初始化脚本 | 低 |

### P1：业务模块化重构

建议周期：3～6 周。

| 优化项 | 说明 | 风险 |
|---|---|---|
| 后端建立 `modules` 业务包结构 | 按 camera/image/inspection/analytics/systemconfig 拆分 | 中 |
| 将统计 SQL 迁入 repository | `LineAnalyticsController`、`StatsController` 优先处理 | 中 |
| 拆分 `LineAnalyticsView.vue` | 图表卡片、配置弹窗、模板管理、聚合任务拆分 | 中 |
| 拆分 `ConfigView.vue` | 系统配置各 Tab 拆成子模块 | 中 |
| 建立 DTO/VO 命名规范 | Request/Response/Query/PageResult | 中低 |
| 建立导入导出服务层 | 配置导入、模板下载、错误明细统一 | 中 |
| 操作日志中心标准化 | 查询、清空、删除、审计范围统一 | 中低 |

### P2：架构增强和长期演进

建议周期：6 周以上，结合新需求推进。

| 优化项 | 说明 | 风险 |
|---|---|---|
| 引入 Flyway/Liquibase | 管理数据库升级脚本 | 中 |
| 建立异步任务与任务状态表 | 聚合任务、清理任务、导出任务统一管理 | 中 |
| 报表引擎配置模型稳定化 | 图表维度、指标、筛选、布局持久化 | 中高 |
| 引入更完整权限模型 | 菜单权限、按钮权限、数据权限 | 中高 |
| 引入自动化 E2E 测试流水线 | 安装包级端到端验收 | 中 |
| 可观测性增强 | 日志、指标、健康检查、诊断导出 | 中 |

---

## 12. 优先级建议清单

| 编号 | 问题 | 影响 | 建议 | 优先级 |
|---|---|---|---|---|
| S-01 | 后端 Controller 文件过大 | 难维护、难测试 | 先抽应用服务，保持接口不变 | P0 |
| S-02 | `LineAnalyticsController` 职责过多 | 产线统计后续需求风险高 | 拆分图表、模板、查询、聚合、导出 | P0 |
| S-03 | `ConfigView.vue` 过大 | 系统配置修改容易互相影响 | 按 Tab 拆分子模块 | P0 |
| S-04 | SQL 分散在 Controller/Service | 字段调整影响不可控 | 建立 repository/mapper 归口 | P0 |
| S-05 | 前端 `package.json` 硬编码 Node 路径 | 新环境构建失败 | 改标准 npm scripts | P0 |
| S-06 | DTO/VO 数量偏少 | 协议边界不清 | 建立 Request/Response DTO 规范 | P1 |
| S-07 | 数据库初始化逻辑混在业务代码 | 安装升级不可控 | 迁移到 SQL migration | P1 |
| S-08 | 页面状态组件不统一 | 用户体验不一致 | 建立 BaseEmpty/BaseError/BaseLoading | P0 |
| S-09 | 路由中文疑似编码异常 | 菜单/标题显示风险 | 全项目 UTF-8 检查 | P0 |
| S-10 | 测试未完全进入工程结构 | 回归依赖人工 | 增加后端集成测试和前端 E2E | P1 |
| S-11 | 发布目录规范不够稳定 | 安装包维护成本高 | 统一 app/config/data/logs/db/docs 结构 | P1 |
| S-12 | 新旧接口兼容逻辑未隔离 | 后续协议演进困难 | 标记 deprecated，隔离 legacy 包 | P1 |

---

## 13. 推荐落地顺序

建议按以下顺序推进，避免重构影响正在使用的功能：

1. **冻结当前对外接口协议**：三类相机上报接口先不随意变更。
2. **先补测试再拆代码**：相机状态、图像记录、产线统计、系统配置四个核心模块先建立回归用例。
3. **先抽前端公共组件**：分页、空状态、错误提示、确认弹窗。
4. **先瘦身后端 Controller**：不改数据库、不改接口，只把业务逻辑下沉到 Service。
5. **再拆大页面组件**：确保视觉和交互不变。
6. **归拢 SQL 与数据库脚本**：降低后续表结构调整风险。
7. **最后做目录迁移**：避免一次改动路径过多导致回归成本过高。

---

## 14. 结论

当前项目已经从产品原型逐步演进为可安装、可上报、可查看、可配置、可统计的本地化系统。当前最大的结构性问题不是“功能缺失”，而是快速迭代后形成的代码聚合度过高：后端大 Controller、大 Service 较多，前端大页面 SFC 较多，SQL 与业务逻辑交织较多。

后续优化建议坚持三个原则：

1. **不破坏当前可用功能**：任何重构都应保持接口和页面行为兼容。
2. **按业务模块拆分**：围绕相机状态、图像记录、检测上报、产线统计、系统配置进行模块化。
3. **以测试兜底**：每次结构调整前后都执行 P0 回归，确保安装包和核心页面可用。

如果按 P0 → P1 → P2 渐进推进，项目可以在不影响交付的前提下，逐步提升可维护性、可测试性和后续需求承载能力。
