# 交付稳定性与现场体验优化开发记录（2026-06-17）

## 1. 开发范围

本轮按三档优先级推进：

### P0 交付稳定性
- 新增安装包端到端验收脚本：`scripts/install-package-acceptance.ps1`。
- 新增数据库迁移执行脚本：`scripts/database-migrate.ps1`。
- `scripts/database-init.ps1` 增加迁移脚本自动执行，支持 MySQL 初始化后一键补齐增量结构。
- 新增迁移目录：`scripts/db/migrations`。
- 新增查询索引迁移：`V20260617_001_indexes_for_query.sql`。
- 新增 100 路相机心跳压测脚本：`scripts/perf-100-camera-heartbeat.mjs`。
- 新增 100 张图片批量上传压测脚本：`scripts/perf-100-defect-image-upload.mjs`。

### P1 现场使用体验
- 图像记录页增强筛选：关键字、严重度、缺陷类型、时间范围、查询、重置。
- `/api/v1/defect-images/recent` 增强后端查询参数：`keyword`、`severity`、`defectType`、`startTime`、`endTime`。
- 系统配置导入失败提示增加处理建议，帮助现场人员定位 Excel 行和字段问题。
- 系统配置层级树增加“展开层级 / 收起层级”快捷操作，默认仍保持收起。
- 产线统计图表配置弹窗增加推荐配置卡片，支持一键套用质量趋势、缺陷排行、工位异常、产品缺陷矩阵。
- 操作日志中心增加高级查询：操作人、请求方法、请求地址、IP、最小耗时、最大耗时。

### P2/P3 代码可维护性
- 新增 `OperationLogQueryDTO`，统一操作日志查询条件。
- 新增 `OperationLogRepository`，将操作日志 SQL 从 Controller 下沉到 Repository 层。
- `SysOperationLogController` 瘦身，仅保留协议入参、CSV 输出和调用 Repository。
- 为图像记录和操作日志高频查询补充数据库索引迁移。

## 2. 关键文件

| 类型 | 文件 |
|---|---|
| 前端图像记录 | `frontend/src/components/CameraImageRecords.vue` |
| 前端产线统计 | `frontend/src/views/stats/LineAnalyticsView.vue` |
| 前端系统配置 | `frontend/src/views/system/ConfigView.vue` |
| 前端导入弹窗 | `frontend/src/views/system/config/ConfigImportDialog.vue` |
| 前端操作日志 | `frontend/src/views/system/OperationLog.vue` |
| 图片记录接口 | `fact-product/src/main/java/com/wms/controller/DefectImageController.java` |
| 图片服务 | `fact-product/src/main/java/com/wms/service/IDefectImageService.java`、`DefectImageServiceImpl.java` |
| 操作日志 Controller | `fact-product/src/main/java/com/wms/controller/SysOperationLogController.java` |
| 操作日志 Repository | `fact-product/src/main/java/com/wms/repository/log/OperationLogRepository.java` |
| 操作日志 DTO | `fact-product/src/main/java/com/wms/dto/log/OperationLogQueryDTO.java` |
| 安装验收 | `scripts/install-package-acceptance.ps1` |
| 数据库迁移 | `scripts/database-migrate.ps1`、`scripts/db/migrations/V20260617_001_indexes_for_query.sql` |
| 压测脚本 | `scripts/perf-100-camera-heartbeat.mjs`、`scripts/perf-100-defect-image-upload.mjs` |

## 3. 验证结果

| 验证项 | 结果 | 证据 |
|---|---|---|
| 前端构建 | PASS | `npm run build` 成功 |
| 后端测试 | PASS | `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` |
| 数据库迁移 | PASS | `database-migrate.ps1` 成功，重复执行可跳过 |
| 安装包端到端验收 | PASS | `test-reports/安装包端到端验收-20260617-222921.md` |
| 100 路相机心跳压测 | PASS | 100/100 成功，耗时约 4675ms |
| 100 张图片批量上传压测 | PASS | 100 张上传成功，缺陷数 100，耗时约 5690ms |
| 全量自动化 QA | PASS | RunId `20260617142933`，45/45 通过 |

## 4. 后续建议

1. 安装包验收脚本当前默认不主动执行服务停止/启动；正式发版前建议在测试机使用 `-VerifyServiceControl` 跑一轮。
2. `database-init.ps1 -ResetSchema` 会重建最终模型，生产环境执行前必须先备份。
3. 压测脚本目前复用系统已有相机配置；正式压力测试建议准备 100 台真实或独立配置相机编号。
4. 下一轮可继续拆分 `LineAnalyticsController` 和 `LineAnalyticsView.vue`，将动态图表查询构建和看板写操作进一步模块化。
