## `line-summary` 响应里与「数据源 / 粒度 / 筛选诚实度」相关的键

前端据此决定展示口径 —— **前端不要硬编码这些语义，一律读后端回传的值**。

| 键 | 含义 |
|---|---|
| `dataSource` | `DEFECT_IMAGE`（现场主通道）或 `AGGREGATION`（图片表无数据时兜底） |
| `dataFreshness` / `aggregationStale` / `aggregationWatermark` | 只有 `AGGREGATION` 才有"陈旧"概念；图片通道恒 `REALTIME` / `false` |
| `trendGranularity` | `HOUR`（跨度 < 3 天）或 `DAY`（≥ 3 天，点数从上千降到几十） |
| `ignoredFilters` | 被丢弃的筛选（当前仅 `productId`：图片事实表不记录产品） |
| `ignoredFilterMessage` | 丢弃原因的人话说明，前端就地提示，**不静默吞掉** |
| `hourlyHeatmap` / `shiftCompare` / `shiftNames` / `dailyCalendar` | 时段热力图 / 班次对比 / 日历热力图的数据块 |
| `plantOverview.dayOverDay*` | 等长前移窗口环比（`dayOverDayRate` 为 null 表示前窗口无数据，显示「—」） |

## `daily-reports` 端点的口径约定

| 端点 | 口径要点 |
|---|---|
| `GET /daily-reports` | 历史日读快照表 `fact_daily_line_report`（每天 00:05 物化昨天），当天实时算并回传 `dataSource=REALTIME` |
| `GET /daily-reports/calendar` | 全月每天一格，**多取一天做计数差分基线**，只有当月内相邻日才成立计数差分；返回 `lines` 供产线胶囊 Tab 使用 |
| `GET /daily-reports/{date}/breakdown` | 汇总取快照；明细（缺陷类型 TOP5 / 相机 TOP5 / 24 小时分布 / 白天 0 上传）走图片事实表实时算；超 31 天归档后 `detailsAvailable=false` → 页面显示「明细已归档」 |
| `POST /daily-reports/{date}/regenerate` | **仅 ADMIN**。幂等覆盖（DELETE + INSERT 同事务）；补算历史日**不拍计数快照**（当前计数已含之后上传，差分必错）→ 计数列留 NULL，页面显示「率待积累」 |
| `GET /daily-reports/export` | 区间内快照行 + 今天实时行一起导出；列含日期 / 产线 / 指标 / 数据源 |

**缺陷率双口径**（2026-11-17 现场实测后确立）：

- **主口径（计数）**：缺陷率(‰) = NG 计数**日增差分** ÷ 检测总数**日增差分** × 1000。
  数据源是 `camera_status.total_count / ok_count / ng_count` 的**日终快照差分** ——
  首日无前日快照 → NULL；计数器被重置（差分为负）→ 也按 NULL 处理。
- **辅口径（图片）**：缺陷图片数列（通道真实上传量）。
  ⚠️ 图片通道**只上报缺陷图**（每张必带缺陷框）→ 图片口径缺陷率**恒为 1000‰**（分母=分子），
  **无区分度**，只作辅助展示，不作缺陷率结论。

**口径版本化**：表上有 `snapshot_version`，改口径只影响此版本之后的日期，历史快照数字不变。
