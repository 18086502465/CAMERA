# 渐进优化执行记录（2026-06-16 第二轮）

## 本轮目标
按“冻结接口 → 补测试 → 抽组件 → 瘦 Controller → 归拢 SQL → 模块化目录”的方向继续小步优化：

- 图像记录页分页统一使用 `BasePagination`。
- 抽取统一空/异常/加载状态组件。
- 系统配置页抽取导入弹窗、删除/修改依赖风险确认逻辑。
- 相机状态页拆分筛选与表格计算逻辑。
- 开始瘦身 `LineAnalyticsController`、`StatsController`，将部分产线统计 SQL 归拢到 Repository。

## 已完成变更

### 前端

1. 公共组件
   - 保留并验证 `frontend/src/components/common/BasePagination.vue`。
   - 保留并验证 `BaseState.vue`、`BaseEmptyState.vue`、`BaseErrorState.vue`、`BaseLoadingState.vue`。
   - `frontend/src/components/EmptyState.vue` 作为兼容封装，减少旧页面改造风险。

2. 图像记录页
   - `frontend/src/components/CameraImageRecords.vue` 已使用 `BasePagination` 承接单个相机展开后的历史分页。
   - 分页默认值继续走全局配置：`10 / 20 / 50 / 100`。

3. 系统配置页
   - 新增 `frontend/src/views/system/config/ConfigImportDialog.vue`，将整体导入弹窗独立为组件。
   - 接入 `frontend/src/views/system/config/useConfigRiskConfirm.js`，统一处理：
     - 删除前依赖查询与阻断提示。
     - 修改前依赖风险提示。
     - 全量导入二次确认。
   - `ConfigView.vue` 分页改为 `BasePagination`。
   - 配置层级树默认收起，避免页面首次进入信息过载。
   - 补齐配置树、计数、层级上下文标题、层级过滤参数、用户页新增按钮等运行时缺失定义。

4. 相机状态页
   - 保留 `frontend/src/views/camera/useCameraStatusTable.js`，继续承接搜索、状态筛选、异常优先、分页和 KPI 计算逻辑。

### 后端

1. `StatsController` 瘦身
   - 新增/扩展 `fact-product/src/main/java/com/wms/repository/stats/StatsQueryRepository.java`：
     - `queryProductShiftYieldRows`
     - `queryYieldTrendRows`
     - `queryStationRankingRows`
     - `queryBrandCompareRows`
   - `StatsController` 已移除直接 `JdbcTemplate` 字段，相关统计查询改为 Repository 调用。

2. `LineAnalyticsController` 初步瘦身
   - 扩展 `fact-product/src/main/java/com/wms/repository/analytics/LineAnalyticsRepository.java`：
     - 元数据查询。
     - 看板列表查询。
     - 默认看板查询。
     - 看板详情查询。
     - 图表详情与图表列表查询。
   - `LineAnalyticsController` 中 `/dashboards` 与私有查询方法改为调用 Repository。

## 验证结果

- 前端构建：通过
  - 命令：`npm run build`
  - 目录：`D:\45344\0004-软件源码\frontend`
  - 结果：Vite build 成功；仍存在既有 chunk 体积提示，不影响构建。

- 后端测试：通过
  - 命令：`D:\45344\0000-jar\apache-maven-3.8.6\bin\mvn.cmd test`
  - 目录：`D:\45344\0004-软件源码\fact-product`
  - 结果：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`。

## 风险与后续建议

1. `LineAnalyticsController` 仍承载较多写入、聚合、导出和动态 SQL，建议下一轮按以下顺序继续拆：
   - 看板/图表写操作 → `AnalyticsDashboardRepository` 或当前 `LineAnalyticsRepository`。
   - 日聚合任务表读写 → `AnalyticsAggregationRepository`。
   - 动态图表查询构建 → Service/QueryBuilder。

2. 系统配置页本轮优先修复运行时缺失定义与抽组件；后续建议继续拆：
   - `useConfigTree`：配置层级树和上下文过滤。
   - `useConfigTable`：分页、列表加载、行标准化。
   - `useConfigEditor`：新增/编辑表单组装。

3. `ConfigView.vue` 仍较大，建议下一轮避免大段重写，继续以小组件/Composable 的方式降低单文件复杂度。
