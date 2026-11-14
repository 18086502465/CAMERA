package com.wms.analytics.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.analytics.infra.repository.AnalyticsAggregationRepository;
import com.wms.analytics.infra.repository.AnalyticsDashboardRepository;
import com.wms.analytics.infra.repository.LineAnalyticsQueryRepository;
import com.wms.analytics.infra.repository.LineAnalyticsRepository;
import com.wms.shared.common.ResultVO;
import com.wms.shared.common.TtlCache;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 产线统计 P0 接口。
 *
 * 首期直接基于 agg_quality_30m / agg_defect_type_30m 查询，
 * 后续动态报表配置仍使用 cfg_analytics_dashboard / cfg_analytics_widget 扩展。
 *
 * <p><b>DDD P3 批次④</b>：全部 SQL 已下沉到 analytics 域的四个 Repository
 * （查询 / 看板配置 / 聚合任务 / 元数据），本类只保留 HTTP 编排、
 * WHERE 拼装与响应封装。
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class LineAnalyticsController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(LineAnalyticsController.class);

    @Autowired
    private LineAnalyticsRepository lineAnalyticsRepository;

    @Autowired
    private LineAnalyticsQueryRepository queryRepository;

    @Autowired
    private AnalyticsDashboardRepository dashboardRepository;

    @Autowired
    private AnalyticsAggregationRepository aggregationRepository;

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DAILY_AGGREGATION_THRESHOLD_DAYS = 3;
    /** 近期窗口自动刷新天数：必须覆盖「日聚合阈值」，否则 3 天窗查询会落到未物化的日期上 */
    private static final int RECENT_REFRESH_WINDOW_DAYS = DAILY_AGGREGATION_THRESHOLD_DAYS;
    /** 近期窗口刷新间隔（与 @Scheduled 的 fixedDelay 保持一致，仅用于日志/说明） */
    private static final long RECENT_REFRESH_INTERVAL_MS = 5 * 60 * 1000L;
    /** 查询侧发现聚合落后时，触发后台补齐的最小间隔（防止高频轮询把重建打成风暴） */
    private static final long STALE_TRIGGER_MIN_INTERVAL_MS = 10 * 60 * 1000L;
    /** 孤儿聚合行清理的最小间隔（全表 DELETE + JOIN，很重，不能跟着查询跑） */
    private static final long ORPHAN_PURGE_MIN_INTERVAL_MS = 60 * 60 * 1000L;
    /**
     * 日聚合「失败重试」的最小间隔（NEW-01，2026-11-06）。
     * 成功时靠 {@code lastRunDate} 拦住当天重复执行；这个节流只对「还没成功」生效，
     * 防止一个持续失败的任务每分钟都把单线程执行器占满。
     */
    private static final long SCHEDULED_RETRY_INTERVAL_MS = 10 * 60 * 1000L;
    /** line-summary 响应短缓存：TTL 60s（前端 30s 轮询可命中一半以上），容量按并发筛选组合封顶 */
    private static final long SUMMARY_CACHE_TTL_MS = 60 * 1000L;
    private static final int SUMMARY_CACHE_MAX_ENTRIES = 64;
    private static final String XLSX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService aggregationExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "analytics-daily-aggregation");
        t.setDaemon(true);
        return t;
    });

    /**
     * line-summary 短缓存（v3 H-2 / PERF-03）。
     *
     * <p>一次 {@code /line-summary} 要打 69 次 Repository 聚合查询，页面 30s 轮询时
     * 完全相同的筛选条件会被反复重算。这里按「全部筛选参数 + 时间窗」做键，
     * 60s 内直接复用上一次的响应。
     *
     * <p><b>为什么不缓存到更久</b>：产线是实时看板，超过 1 分钟的数据在现场会被质疑；
     * 12 次/分钟的重复计算已经足够消除。
     * <p><b>为什么不用 Spring Cache</b>：现场离线部署，少一个三方依赖少一条发版链。
     */
    private final TtlCache<String, Map<String, Object>> summaryCache =
            new TtlCache<>(SUMMARY_CACHE_TTL_MS, SUMMARY_CACHE_MAX_ENTRIES);

    /** 近期窗口刷新是否在跑（单线程执行器排队时不再重复提交） */
    private final AtomicBoolean recentRefreshInFlight = new AtomicBoolean(false);
    /** 上次「查询发现落后 → 触发后台补齐」的时间戳 */
    private final AtomicLong lastStaleTriggerAt = new AtomicLong(0L);
    /** 上次孤儿聚合行清理时间戳 */
    private final AtomicLong lastOrphanPurgeAt = new AtomicLong(0L);
    /** 上次「每日聚合」提交时间戳（NEW-01：失败重试节流） */
    private final AtomicLong lastScheduledSubmitAt = new AtomicLong(0L);
    /** 上次近期窗口刷新完成时间（供 /aggregation/schedule 暴露给运维） */
    private volatile long lastRecentRefreshAt = 0L;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    /** 懒建 TransactionTemplate（避免构造期依赖注入顺序问题） */
    private TransactionTemplate txTemplate() {
        TransactionTemplate template = this.txTemplate;
        if (template == null) {
            synchronized (this) {
                if (this.txTemplate == null) {
                    this.txTemplate = new TransactionTemplate(transactionManager);
                }
                template = this.txTemplate;
            }
        }
        return template;
    }

    @GetMapping("/metadata")
    public ResultVO<Map<String, Object>> metadata() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("products", lineAnalyticsRepository.listEnabledProducts());
        data.put("defectTypes", lineAnalyticsRepository.listEnabledDefectTypes());
        data.put("positions", lineAnalyticsRepository.listEnabledPositions());
        data.put("cameras", lineAnalyticsRepository.listEnabledCameras());
        data.put("shifts", lineAnalyticsRepository.listEnabledShifts());

        List<Map<String, Object>> widgetTypes = new ArrayList<>();
        widgetTypes.add(item("KPI", "指标卡", "展示产量、OK、NG、良率等核心指标"));
        widgetTypes.add(item("LINE", "趋势折线", "按时间展示产量、NG、良率趋势"));
        widgetTypes.add(item("BAR", "柱状图", "按产线、工位、相机、产品对比"));
        widgetTypes.add(item("STACK_BAR", "堆叠柱状图", "按缺陷类型堆叠展示"));
        widgetTypes.add(item("RANK", "排行", "展示 Top 缺陷/工位/相机"));
        widgetTypes.add(item("PIVOT", "透视表", "动态生成产品缺陷列"));
        widgetTypes.add(item("PLANT_OVERVIEW", "工厂总览", "厂长视角：规模、风险等级、首要缺陷一页看清"));
        widgetTypes.add(item("PARETO", "帕累托图", "缺陷类型累计占比，定位主要矛盾"));
        widgetTypes.add(item("SEVERITY", "严重度构成", "高/中/低缺陷占比，判断质量风险等级"));
        widgetTypes.add(item("HEATMAP", "时段热力图", "小时×缺陷类型，定位高发时段"));
        data.put("widgetTypes", widgetTypes);

        List<Map<String, Object>> dimensions = new ArrayList<>();
        dimensions.add(item("time", "时间", "30分钟/日粒度"));
        dimensions.add(item("factory", "工厂", "按工厂筛选和汇总"));
        dimensions.add(item("line", "产线", "按产线筛选和对比"));
        dimensions.add(item("workstation", "工位", "按工位定位异常"));
        dimensions.add(item("camera", "相机", "按相机追溯"));
        dimensions.add(item("product", "产品", "按产品动态缺陷列"));
        dimensions.add(item("shift", "班次", "白班/夜班"));
        dimensions.add(item("defectType", "缺陷类型", "按标准缺陷配置统计"));
        dimensions.add(item("position", "缺陷位置", "按位置分析"));
        dimensions.add(item("severity", "严重度", "HIGH/MEDIUM/LOW"));
        data.put("dimensions", dimensions);

        return ResultVO.success(data);
    }

    @GetMapping("/line-summary")
    public ResultVO<Map<String, Object>> lineSummary(@RequestParam(required = false) String startTime,
                                                     @RequestParam(required = false) String endTime,
                                                     @RequestParam(required = false) Long factoryId,
                                                     @RequestParam(required = false) Long lineId,
                                                     @RequestParam(required = false) Long workstationId,
                                                     @RequestParam(required = false) Long cameraId,
                                                     @RequestParam(required = false) Long productId,
                                                     @RequestParam(required = false) Long shiftId,
                                                     @RequestParam(required = false) Long defectTypeId,
                                                     @RequestParam(required = false) String severity,
                                                     @RequestParam(required = false) String defectType) {
        LocalDateTime start = parseTime(startTime, LocalDateTime.of(LocalDate.now(), LocalTime.MIN));
        LocalDateTime end = parseTime(endTime, LocalDateTime.of(LocalDate.now(), LocalTime.MAX.withNano(0)));
        return ResultVO.success(buildSummaryCached(start, end, factoryId, lineId, workstationId, cameraId,
                productId, shiftId, defectTypeId, severity, defectType));
    }

    /**
     * 带短缓存的汇总入口（v3 H-2 / PERF-03）。
     *
     * <p>键 = 全部筛选参数 + 时间窗。缓存值<b>只读</b>（本方法返回后只经 Jackson 序列化，
     * 没有任何调用方原地修改），因此可以直接复用 Map 实例。
     */
    private Map<String, Object> buildSummaryCached(LocalDateTime start, LocalDateTime end,
                                                  Long factoryId, Long lineId, Long workstationId, Long cameraId,
                                                  Long productId, Long shiftId, Long defectTypeId, String severity,
                                                  String defectType) {
        String cacheKey = summaryCacheKey(start, end, factoryId, lineId, workstationId, cameraId,
                productId, shiftId, defectTypeId, severity, defectType);
        Map<String, Object> cached = summaryCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Map<String, Object> built = buildSummary(start, end, factoryId, lineId, workstationId, cameraId,
                productId, shiftId, defectTypeId, severity, defectType);
        summaryCache.put(cacheKey, built);
        return built;
    }

    private String summaryCacheKey(LocalDateTime start, LocalDateTime end,
                                   Long factoryId, Long lineId, Long workstationId, Long cameraId,
                                   Long productId, Long shiftId, Long defectTypeId, String severity,
                                   String defectType) {
        StringBuilder key = new StringBuilder(96);
        key.append(start.format(DT)).append('~').append(end.format(DT));
        key.append('|').append(factoryId).append('|').append(lineId).append('|').append(workstationId)
                .append('|').append(cameraId).append('|').append(productId).append('|').append(shiftId)
                .append('|').append(defectTypeId)
                .append('|').append(severity == null ? "" : severity.trim().toUpperCase(Locale.ROOT))
                .append('|').append(defectType == null ? "" : defectType.trim());
        return key.toString();
    }

    private Map<String, Object> buildSummary(LocalDateTime start, LocalDateTime end,
                                             Long factoryId, Long lineId, Long workstationId, Long cameraId,
                                             Long productId, Long shiftId, Long defectTypeId, String severity,
                                             String defectType) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("结束时间不能早于开始时间");
        }
        boolean daily = shouldUseDailyAggregation(start, end);
        if (daily) {
            // ================================================================
            // v3 H-1（PERF-02，2026-11-06）：**读路径不再写库**
            // ================================================================
            // 原实现（勿回退到该版本）：
            //   aggregationRepository.ensureDailyAggregationTables();
            //   rebuildQualityDaily(start, end, ...);   // DELETE + INSERT 全范围
            //   rebuildDefectDaily(start, end, ...);    // DELETE + INSERT 全范围
            //   purgeOrphanAggregationRows();           // 4 张表全表 DELETE + JOIN
            // 前端 30s 轮询 = 每 30s 一次全范围重建：
            //   ① 多用户并发互相覆盖中间态（DELETE 已提交、INSERT 未完成时别人查到的就是空）；
            //   ② binlog / redo 写放大，磁盘 IO 与主从延迟全被报表吃掉；
            //   ③ 一个"查询接口"在 DB 侧实际是写事务，锁等待反过来拖慢上传主链路。
            //
            // 现在：查询只读物化表；新鲜度由下面两个任务保证 ——
            //   ① @Scheduled refreshRecentDailyAggregation()：每 5 分钟刷新近 3 天窗口；
            //   ② 查询发现聚合落后时，异步提交一次补齐（10 分钟节流，不阻塞本次请求）；
            // 表结构自检保留（INFORMATION_SCHEMA 幂等查询，不执行 DDL），
            // 防止运行期表被删后直接 500。
            aggregationRepository.ensureDailyAggregationTables();
        }

        // ★ 2026-11-05 数据源优先级修正（详见 buildSummary 的完整说明）
        //   现场设备走 defect-images/upload 通道，只写 defect_image_*，**不写** agg_*；
        //   agg_quality_30m 的唯一写入来源是 batch-upload（冻结契约）+ 日聚合重建。
        //   现场实测 agg_quality_30m 只剩 5 条**孤儿行**（line_id 指向早已删除的产线）。
        //   旧判定「聚合表完全无数据才兜底」会被这些孤儿行骗过 →
        //   不筛选产线时显示聚合表的 4 条，筛选产线时触发兜底显示图片表的 3041 条
        //   → **子集比全集还大**，用户完全无法理解。
        //   改为：**图片事实表优先**（它是现场真正在用的通道），聚合表仅在图片表无数据时兜底。
        Map<String, Object> imageData = buildDefectImageSummary(
                start, end, factoryId, lineId, workstationId, cameraId, severity, defectType, shiftId);
        if (longVal(map(imageData.get("kpi")).get("totalCount")) > 0) {
            Map<String, Object> data = new LinkedHashMap<>(imageData);
            data.put("aggregationLevel", daily ? "DAY" : "30M");
            data.put("aggregationMessage", daily ? "当前时间范围较大，已自动使用日聚合表查询。" : "当前使用30分钟聚合表查询。");
            data.put("aggregationThresholdDays", DAILY_AGGREGATION_THRESHOLD_DAYS);
            data.put("dataSource", "DEFECT_IMAGE");
            data.put("dataSourceMessage", "当前使用缺陷图片事实表统计（现场设备实际上报通道）；该通道只上报缺陷图，良率不可计算。");
            // NEW-06（2026-11-06 v5 评审）：两条数据源分支的响应键必须一致。
            //   改造前「聚合新鲜度」三个键只挂在聚合表分支上，而现场 99% 的请求走这条
            //   图片事实表分支 → 前端读 aggregationStale 恒为 undefined，判断逻辑静默失效。
            //   这里补成明确值：图片事实表是**实时读**，不存在"聚合陈旧"概念。
            data.put("dataFreshness", "REALTIME");
            data.put("aggregationStale", Boolean.FALSE);
            data.put("aggregationWatermark", null);
            data.put("aggregationMessage", "数据实时取自缺陷图片事实表，不依赖聚合表。");
            // ★ 映射异常与数据源无关（独立指标），但必须补上 ——
            //   否则图片表路径的响应会少这个键，与聚合表路径结构不一致。
            data.put("mappingExceptions", queryRepository.queryOpenMappingExceptions());
            data.put("severityDistribution", data.get("severityDistribution"));
            data.put("defectPareto", buildPareto(data.get("defectRanking")));
            data.put("plantOverview", buildPlantOverview(data));
            data.put("defectTrend", buildDefectTrend(data.get("trend")));
            return data;
        }

        String qualityTable = daily ? "agg_quality_day" : "agg_quality_30m";
        String defectTable = daily ? "agg_defect_type_day" : "agg_defect_type_30m";
        String timeColumn = daily ? "date_bucket" : "time_bucket";

        List<Object> qualityParams = new ArrayList<>();
        String qualityWhere = buildQualityWhere(timeColumn, daily, start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId, qualityParams);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("aggregationLevel", daily ? "DAY" : "30M");
        data.put("aggregationMessage", daily ? "当前时间范围较大，已自动使用日聚合表查询。" : "当前使用30分钟聚合表查询。");
        data.put("aggregationThresholdDays", DAILY_AGGREGATION_THRESHOLD_DAYS);
        Map<String, Object> kpi = queryRepository.queryKpi(qualityTable, qualityWhere, qualityParams);
        long total = longVal(kpi.get("totalCount"));
        long ok = longVal(kpi.get("okCount"));
        long ng = longVal(kpi.get("ngCount"));
        kpi.put("yieldRate", total > 0 ? Math.round(ok * 10000.0 / total) / 100.0 : 0.0);
        kpi.put("ngRate", total > 0 ? Math.round(ng * 10000.0 / total) / 100.0 : 0.0);
        data.put("kpi", kpi);

        data.put("trend", queryRepository.queryTrend(qualityTable, timeColumn, qualityWhere, qualityParams));

        List<Object> defectParams = new ArrayList<>();
        String defectWhere = buildDefectWhere(timeColumn, daily, start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId, defectTypeId, severity, defectParams);
        data.put("defectRanking", queryRepository.queryDefectRanking(defectTable, defectWhere, defectParams));
        List<Object> stationParams = new ArrayList<>();
        String stationWhere = buildQualityWhereAlias("q", timeColumn, daily, start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId, stationParams);
        data.put("stationRanking", queryRepository.queryStationRanking(qualityTable, stationWhere, stationParams));
        List<Object> cameraParams = new ArrayList<>();
        String cameraWhere = buildQualityWhereAlias("q", timeColumn, daily, start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId, cameraParams);
        data.put("cameraRanking", queryRepository.queryCameraRanking(qualityTable, cameraWhere, cameraParams));
        data.put("defectPivot", buildDefectPivot(defectTable, timeColumn, daily, start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId));
        data.put("mappingExceptions", queryRepository.queryOpenMappingExceptions());

        // ★ 2026-11-05：走到这里说明「缺陷图片事实表」在当前范围没有数据
        //   （可能查的是图片保留期之外的历史区间）。此时用质量聚合表兜底。
        data.put("dataSource", "AGGREGATION");
        data.put("dataSourceMessage", "当前范围缺陷图片事实表无数据，已切换到质量聚合表统计。");
        // NEW-06：与 DEFECT_IMAGE 分支保持同一套键（前端只需判断 dataFreshness）
        data.put("dataFreshness", "MATERIALIZED");

        // v3 H-1：聚合表水位自检（两列只读查询）。落后时**绝不在请求线程里重建**，
        // 只异步提交一次补齐任务（10 分钟节流），并如实告诉前端「本次结果可能不完整」。
        LocalDate expectEnd = end.toLocalDate();
        LocalDate watermark = aggregationRepository.latestQualityDay();
        boolean stale = watermark == null || watermark.isBefore(expectEnd);
        data.put("aggregationWatermark", watermark == null ? null : watermark.toString());
        data.put("aggregationStale", stale);
        if (stale) {
            // ⚠ 只有「曾经物化过、现在落后」才自动补齐。
            //   watermark 为 null 说明日聚合表从未有数据（现场设备走图片通道、
            //   agg_quality_30m 本身就是空表），补齐只会产出 0 行并把
            //   analytics_aggregation_job 刷成一堆无意义的 AUTO_STALE 记录。
            //   这种情况交给 5 分钟刷新任务（它每次都会重建近期窗口，源表有数据就会自动接上），
            //   历史区间则由运维显式调用 /aggregation/daily/run。
            boolean submitted = watermark != null
                    && requestAsyncAggregation(start.toLocalDate(), end.toLocalDate(), factoryId, lineId);
            data.put("aggregationMessage", "日聚合表最新水位为 " + (watermark == null ? "无数据" : watermark.toString())
                    + "，落后于查询区间末 " + expectEnd + "；本次结果仅含已物化部分。"
                    + (submitted
                        ? "已提交后台补齐任务，稍后刷新即可看到完整数据。"
                        : "聚合表尚未物化（或刚提交过补齐），历史区间可用 POST /api/v1/analytics/aggregation/daily/run 显式补齐。"));
        }

        // ---- 工厂厂长视角指标（严重度分布 / 帕累托 / 总览 / 缺陷趋势）----
        // ⚠️ 严重度分布必须用「与主数据源同源」的查询，否则数据源切换时会被空结果覆盖。
        //   2026-11-05 起主数据源是「缺陷图片事实表」，它自带 severityDistribution；
        //   走到这里说明用的是聚合表 → 用聚合表口径重新查。
        data.put("severityDistribution", buildSeverityDistribution(defectTable, timeColumn, daily, start, end,
                factoryId, lineId, workstationId, cameraId, productId, shiftId, severity));
        data.put("defectPareto", buildPareto(data.get("defectRanking")));
        data.put("plantOverview", buildPlantOverview(data));
        data.put("defectTrend", buildDefectTrend(data.get("trend")));
        return data;
    }

    /**
     * 缺陷趋势 —— 与质量趋势区分：只画缺陷数与高危缺陷数，不画良率。
     * 厂长的关注点是「问题在变多还是变少」，不是良率曲线。
     */
    private List<Map<String, Object>> buildDefectTrend(Object trend) {
        List<Map<String, Object>> rows = listMap(trend);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>(r);
            item.put("defectCount", longVal(r.get("defectCount")));
            item.put("ngCount", longVal(r.get("ngCount")));
            result.add(item);
        }
        return result;
    }

    /**
     * 严重度分布（高/中/低）—— 工厂厂长判断质量风险等级的第一眼指标。
     */
    private List<Map<String, Object>> buildSeverityDistribution(String defectTable, String timeColumn, boolean daily,
                                                                 LocalDateTime start, LocalDateTime end,
                                                                 Long factoryId, Long lineId, Long workstationId,
                                                                 Long cameraId, Long productId, Long shiftId, String severity) {
        List<Object> params = new ArrayList<>();
        String where = buildDefectWhere(timeColumn, daily, start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId, null, severity, params);
        return queryRepository.querySeverityDistribution(defectTable, where, params);
    }

    /**
     * 帕累托：缺陷类型排行 + 累计占比。
     * 厂长用帕累托一眼看出「80% 的问题来自哪几类缺陷」，比单纯排行更可决策。
     */
    private List<Map<String, Object>> buildPareto(Object defectRanking) {
        List<Map<String, Object>> rows = listMap(defectRanking);
        long total = 0;
        for (Map<String, Object> r : rows) total += longVal(r.get("defectCount"));
        long acc = 0;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>(r);
            long count = longVal(r.get("defectCount"));
            acc += count;
            item.put("totalDefect", total);
            item.put("accumulatedDefect", acc);
            item.put("accumulatedRate", total > 0 ? Math.round(acc * 10000.0 / total) / 100.0 : 0.0);
            result.add(item);
        }
        return result;
    }

    /**
     * 工厂总览 —— 面向厂长的「一页看清」汇总卡。
     *
     * <p>字段含义：
     * <ul>
     *   <li>{@code detectionTotal} 检测总量；{@code defectTotal} 缺陷总数；</li>
     *   <li>{@code highDefect} 高危缺陷数；{@code highRate} 高危占比（%）；</li>
     *   <li>{@code affectedWorkstation} 受影响工位数；{@code affectedCamera} 涉及相机数；</li>
     *   <li>{@code topDefectName} 首要缺陷类型；{@code topDefectRate} 其占比（%）；</li>
     *   <li>{@code yieldRateAvailable} 良率是否可算（图片通道无良品样本 → false）。</li>
     * </ul>
     */
    private Map<String, Object> buildPlantOverview(Map<String, Object> data) {
        Map<String, Object> kpi = map(data.get("kpi"));
        Map<String, Object> overview = new LinkedHashMap<>();
        long detection = longVal(kpi.get("totalCount"));
        long defect = longVal(kpi.get("defectCount"));
        long high = longVal(kpi.get("highCount"));
        overview.put("detectionTotal", detection);
        overview.put("defectTotal", defect);
        overview.put("highDefect", high);
        overview.put("highRate", defect > 0 ? Math.round(high * 10000.0 / defect) / 100.0 : 0.0);
        overview.put("defectPerImage", detection > 0 ? Math.round(defect * 10000.0 / detection) / 100.0 : 0.0);
        overview.put("affectedWorkstation", longVal(kpi.get("workstationCount")));
        overview.put("affectedCamera", longVal(kpi.get("cameraCount")));
        overview.put("yieldRateAvailable", !Boolean.FALSE.equals(kpi.get("yieldRateAvailable")));
        Object rate = kpi.get("yieldRate");
        overview.put("yieldRate", rate == null ? null : rate);
        List<Map<String, Object>> ranking = listMap(data.get("defectRanking"));
        if (!ranking.isEmpty()) {
            Map<String, Object> top = ranking.get(0);
            overview.put("topDefectName", top.get("defectName"));
            long topCount = longVal(top.get("defectCount"));
            overview.put("topDefectCount", topCount);
            overview.put("topDefectRate", defect > 0 ? Math.round(topCount * 10000.0 / defect) / 100.0 : 0.0);
        } else {
            overview.put("topDefectName", null);
            overview.put("topDefectCount", 0L);
            overview.put("topDefectRate", 0.0);
        }
        overview.put("dataSource", data.get("dataSource"));
        // ★ 2026-11-14 看板 D：等长前移窗口环比（较上一时段）。
        //   仅图片通道计算（appendWindowCompare 塞进 kpi）；聚合表通道无此键 → 前端显示「—」。
        Object prevWindow = kpi.get("prevWindowCount");
        if (prevWindow != null) {
            long prev = longVal(prevWindow);
            overview.put("dayOverDayCurrent", detection);
            overview.put("dayOverDayPrevious", prev);
            overview.put("dayOverDayRate", prev > 0
                    ? Math.round((detection - prev) * 10000.0 / prev) / 100.0 : null);
            overview.put("dayOverDayLabel", kpi.get("dayOverDayLabel"));
        }
        return overview;
    }

    /**
     * 基于「缺陷图片事实表」的统计（聚合表无数据时的兜底数据源）。
     *
     * <p>现场设备通过 {@code /api/v1/defect-images/upload} 上报，
     * 该通道只写 {@code defect_image_batch / defect_image_file / defect_image_box}，
     * <b>不写</b> {@code agg_quality_30m}。因此统计页若只读聚合表，
     * 在只有图片上报的现场就永远是 0 —— 实测线上 KPI 全为 0 正是此原因。
     *
     * <p>口径说明（与聚合表不同，前端需按 dataSource 区分展示）：
     * <ul>
     *   <li>{@code totalCount} = 缺陷图片张数（每张图代表一次检测事件）；</li>
     *   <li>{@code defectCount} = 缺陷框总数（一张图可能有多个缺陷框）；</li>
     *   <li>{@code okCount} = 0，{@code ngCount} = 缺陷图片数
     *       —— <b>图片通道只上报缺陷图，没有良品样本</b>，所以良率不可计算，
     *       前端对 {@code dataSource=DEFECT_IMAGE} 应显示「—」而不是 0%；</li>
     *   <li>{@code yieldRate} = null（不可计算），由前端显示「—」。</li>
     * </ul>
     */
    private Map<String, Object> buildDefectImageSummary(LocalDateTime start, LocalDateTime end,
                                                        Long factoryId, Long lineId, Long workstationId,
                                                        Long cameraId, String severity,
                                                        String defectType, Long shiftId) {
        Map<String, Object> data = new LinkedHashMap<>();

        // 图片表的 camera_id 存的是相机编号（camera_code），需经 dim_camera 关联出维度
        String joinCam = " LEFT JOIN dim_camera c ON c.camera_code = f.camera_id ";
        List<Object> params = new ArrayList<>();
        StringBuilder where = buildImageWhere(start, end, factoryId, lineId, workstationId, cameraId,
                defectType, shiftId, params);
        String sevWhere = "";
        if (severity != null && !severity.trim().isEmpty()) {
            sevWhere = " AND b.severity = ? ";
        }

        Map<String, Object> kpi = new LinkedHashMap<>();
        List<Object> kpiParams = new ArrayList<>(params);
        Map<String, Object> imgStat = queryRepository.queryDefectImageKpi(joinCam, where.toString(), kpiParams);
        long imageCount = longVal(imgStat.get("imageCount"));
        long defectCount = longVal(imgStat.get("defectCount"));
        kpi.put("totalCount", imageCount);
        kpi.put("defectCount", defectCount);
        kpi.put("okCount", 0L);
        kpi.put("ngCount", imageCount);
        kpi.put("highCount", longVal(imgStat.get("highCount")));
        kpi.put("mediumCount", longVal(imgStat.get("mediumCount")));
        kpi.put("lowCount", longVal(imgStat.get("lowCount")));
        kpi.put("cameraCount", longVal(imgStat.get("cameraCount")));
        kpi.put("workstationCount", longVal(imgStat.get("workstationCount")));
        kpi.put("yieldRate", null);   // 图片通道无良品样本，良率不可计算
        kpi.put("ngRate", null);
        kpi.put("yieldRateAvailable", false);
        appendWindowCompare(kpi, start, end, joinCam, factoryId, lineId, workstationId, cameraId, defectType, shiftId);
        data.put("kpi", kpi);

        // 趋势：按小时聚合缺陷图与缺陷数
        List<Object> trendParams = new ArrayList<>(params);
        data.put("trend", queryRepository.queryDefectImageTrend(joinCam, where.toString(), trendParams));

        // 缺陷类型排行：按 defect_image_box 的 defect_type（现场口径为中文缺陷名）
        List<Object> rankParams = new ArrayList<>(params);
        if (!sevWhere.isEmpty()) { rankParams.add(severity.trim().toUpperCase(Locale.ROOT)); }
        data.put("defectRanking", queryRepository.queryDefectImageRanking(joinCam, where.toString(), sevWhere, rankParams));

        // 工位排行
        List<Object> stationParams = new ArrayList<>(params);
        data.put("stationRanking", queryRepository.queryDefectImageStationRanking(joinCam, where.toString(), stationParams));

        // 相机排行
        List<Object> cameraParams = new ArrayList<>(params);
        data.put("cameraRanking", queryRepository.queryDefectImageCameraRanking(joinCam, where.toString(), cameraParams));

        // 产品缺陷透视：图片通道无产品维度，用缺陷类型作列
        data.put("defectPivot", queryRepository.queryDefectImagePivot(joinCam, where.toString(), sevWhere, rankParams));

        // 严重度分布
        data.put("severityDistribution", queryRepository.queryDefectImageSeverityDistribution(joinCam, where.toString(), sevWhere, rankParams));

        // ★ 2026-11-14 看板 A / D 新增数据块（均受当前全部筛选约束）：
        //   时段×类型热力图、班次×类型对比（含班次名，供前端图例）。
        //   班次分桶窗口固定取班次定义（白班 08-20 / 夜班 20-08），
        //   与「筛选了某个班次」无关 —— 筛选只收窄数据范围，不改变分桶。
        List<Object> heatParams = new ArrayList<>(params);
        data.put("hourlyHeatmap", queryRepository.queryDefectImageHourlyHeatmap(joinCam, where.toString(), heatParams));
        Map<String, Object> shiftWindows = resolveShiftWindows();
        List<Object> shiftParams = new ArrayList<>(params);
        data.put("shiftCompare", queryRepository.queryDefectImageShiftCompare(joinCam, where.toString(),
                shiftParams, (String) shiftWindows.get("dayStart"), (String) shiftWindows.get("dayEnd")));
        data.put("shiftNames", shiftWindows);

        return data;
    }

    /**
     * 图片通道公共 WHERE 片段（2026-11-14 提取，供环比前移窗口复用）。
     *
     * <p>defectType 为现场实际上报的类型文本（接头/小污点…），与 dim_defect_type 的
     * 标准类型 ID 完全不相交，因此按文本对 defect_image_box 精确匹配（EXISTS）——
     * 与 DefectImageRepository 的口径一致，绝不能用 LIKE（类型名互相包含会放大结果集）。
     *
     * <p>shiftId 按时间窗推导：dim_shift 里白班 08:00-20:00、夜班 20:00-08:00（跨天）。
     * 事实表没有 shift_id 列，只能从 capture_time 的小钟点落在哪个班次窗口来判断。
     */
    private StringBuilder buildImageWhere(LocalDateTime start, LocalDateTime end,
                                          Long factoryId, Long lineId, Long workstationId, Long cameraId,
                                          String defectType, Long shiftId, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE f.capture_time BETWEEN ? AND ? ");
        params.add(Timestamp.valueOf(start));
        params.add(Timestamp.valueOf(end));
        if (factoryId != null) { where.append(" AND c.factory_id = ? "); params.add(factoryId); }
        if (lineId != null) { where.append(" AND c.line_id = ? "); params.add(lineId); }
        if (workstationId != null) { where.append(" AND c.workstation_id = ? "); params.add(workstationId); }
        if (cameraId != null) { where.append(" AND c.camera_id = ? "); params.add(cameraId); }
        if (defectType != null && !defectType.trim().isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM defect_image_box bt " +
                    "WHERE bt.image_id = f.image_id AND bt.defect_type = ?) ");
            params.add(defectType.trim());
        }
        if (shiftId != null) {
            Map<String, Object> shift = resolveShift(shiftId);
            if (!shift.isEmpty()) {
                String s = hhmmss(shift.get("startTime"));
                String e = hhmmss(shift.get("endTime"));
                boolean crossDay = longVal(shift.get("crossDay")) == 1;
                // 时间窗字面量来自 dim_shift 的 LocalTime，无注入面
                if (crossDay) {
                    where.append(" AND (TIME(f.capture_time) >= '").append(s)
                            .append("' OR TIME(f.capture_time) < '").append(e).append("') ");
                } else {
                    // ★ 2026-11-14 线上验证抓到的拼接 bug：这里曾多写一个右括号
                    //   （... < '08:00:00')）→ 白班筛选 SQL 语法错 → 500。
                    //   mock 契约测试不执行真 SQL，这类拼接错误只能靠线上验证暴露。
                    where.append(" AND TIME(f.capture_time) >= '").append(s)
                            .append("' AND TIME(f.capture_time) < '").append(e).append("' ");
                }
            }
        }
        return where;
    }

    /** 等长前移窗口环比：本窗口 vs 紧邻的前一等长窗口（厂长日报「较上一时段」卡）。 */
    private void appendWindowCompare(Map<String, Object> kpi, LocalDateTime start, LocalDateTime end,
                                     String joinCam, Long factoryId, Long lineId, Long workstationId,
                                     Long cameraId, String defectType, Long shiftId) {
        kpi.put("prevWindowCount", null);
        kpi.put("dayOverDayRate", null);
        kpi.put("dayOverDayLabel", null);
        try {
            java.time.Duration span = java.time.Duration.between(start, end);
            if (span.isZero() || span.isNegative()) {
                return;
            }
            List<Object> prevParams = new ArrayList<>();
            StringBuilder prevWhere = buildImageWhere(start.minus(span), start, factoryId, lineId,
                    workstationId, cameraId, defectType, shiftId, prevParams);
            long prevCount = queryRepository.countDefectImages(joinCam, prevWhere.toString(), prevParams);
            long current = longVal(kpi.get("totalCount"));
            kpi.put("prevWindowCount", prevCount);
            if (prevCount > 0) {
                kpi.put("dayOverDayRate", Math.round((current - prevCount) * 10000.0 / prevCount) / 100.0);
            }
            long days = span.toDays();
            long hours = span.toHours();
            kpi.put("dayOverDayLabel", days >= 1 ? "较前 " + days + " 天" : (hours >= 1 ? "较前 " + hours + " 小时" : "较前时段"));
        } catch (Exception ex) {
            // 环比是增强信息，任何失败都不能影响主 KPI
            log.warn("计算前移窗口环比失败（不影响主统计）：{}", ex.getMessage());
        }
    }

    /** 班次定义查询（带空值防御），dim_shift 主键查询 */
    private Map<String, Object> resolveShift(Long shiftId) {
        if (shiftId == null) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> shift = queryRepository.findShiftById(shiftId);
            return shift == null ? new LinkedHashMap<>() : shift;
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    /** LocalTime/Time → 'HH:mm:ss' 字面量（时间窗拼接用） */
    private String hhmmss(Object time) {
        if (time == null) {
            return "00:00:00";
        }
        String s = time.toString();
        return s.length() == 5 ? s + ":00" : s;
    }

    /**
     * 班次对比的分桶窗口：dim_shift 里不跨天且最早开始的为白班，跨天的为夜班。
     * 取不到定义时退化为硬编码 08:00-20:00（与现场 dim_shift 实际配置一致）。
     */
    private Map<String, Object> resolveShiftWindows() {
        Map<String, Object> windows = new LinkedHashMap<>();
        windows.put("day", "白班");
        windows.put("night", "夜班");
        windows.put("dayStart", "08:00:00");
        windows.put("dayEnd", "20:00:00");
        try {
            List<Map<String, Object>> shifts = queryRepository.listShiftsForCompare();
            if (shifts.size() >= 2) {
                windows.put("day", String.valueOf(shifts.get(0).getOrDefault("shiftName", "白班")));
                windows.put("night", String.valueOf(shifts.get(1).getOrDefault("shiftName", "夜班")));
                windows.put("dayStart", hhmmss(shifts.get(0).get("startTime")));
                windows.put("dayEnd", hhmmss(shifts.get(0).get("endTime")));
            }
        } catch (Exception ex) {
            log.warn("读取班次定义失败，班次对比退化为默认 08:00-20:00：{}", ex.getMessage());
        }
        return windows;
    }

    /**
     * 通用查询入口，P0 支持 SUMMARY/TRend/DEFECT_RANK 三类。
     */
    @PostMapping("/query")
    public ResultVO<Map<String, Object>> query(@RequestBody Map<String, Object> body) {
        String startTime = strOrNull(body.get("startTime"));
        String endTime = strOrNull(body.get("endTime"));
        return lineSummary(startTime, endTime,
                longOrNull(body.get("factoryId")), longOrNull(body.get("lineId")), longOrNull(body.get("workstationId")),
                longOrNull(body.get("cameraId")), longOrNull(body.get("productId")), longOrNull(body.get("shiftId")),
                longOrNull(body.get("defectTypeId")), strOrNull(body.get("severity")),
                strOrNull(body.get("defectType")));
    }

    // ==================== P1 动态报表配置 ====================

    @GetMapping("/dashboards")
    public ResultVO<List<Map<String, Object>>> dashboards(@RequestParam(required = false) Long factoryId,
                                                          @RequestParam(required = false) Long ownerUserId) {
        return ResultVO.success(lineAnalyticsRepository.listDashboards(factoryId, ownerUserId));
    }

    @GetMapping("/dashboards/default")
    public ResultVO<Map<String, Object>> defaultDashboard(@RequestParam(required = false) Long factoryId) {
        // 旧版默认看板升级为「工厂质量看板（厂长视角）」
        upgradeLegacyDefaultDashboard(factoryId);
        Map<String, Object> dashboard = findDefaultDashboard(factoryId);
        if (dashboard == null) {
            Long dashboardId = createDefaultDashboard(factoryId);
            dashboard = findDashboard(dashboardId);
        }
        dashboard.put("widgets", listWidgets(longVal(dashboard.get("dashboardId"))));
        return ResultVO.success(dashboard);
    }

    @GetMapping("/dashboards/{id}")
    public ResultVO<Map<String, Object>> dashboardDetail(@PathVariable Long id) {
        Map<String, Object> dashboard = findDashboard(id);
        if (dashboard == null) return ResultVO.fail("看板不存在");
        dashboard.put("widgets", listWidgets(id));
        return ResultVO.success(dashboard);
    }

    @PostMapping("/dashboards")
    public ResultVO<Map<String, Object>> createDashboard(@RequestBody Map<String, Object> body) {
        String name = required(body, "dashboardName", "看板名称不能为空");
        Long factoryId = longOrNull(body.get("factoryId"));
        Long ownerUserId = longOrNull(body.get("ownerUserId"));
        String scopeType = firstNonBlank(strOrNull(body.get("scopeType")), "FACTORY");
        int isDefault = intVal(body.get("isDefault"), 0);
        String layoutJson = jsonValue(body.get("layoutJson"), "{\"cols\":12,\"rowHeight\":120}");
        Long id = dashboardRepository.insertDashboard(name, factoryId, ownerUserId, scopeType, isDefault, layoutJson);
        return dashboardDetail(id);
    }

    @PostMapping("/dashboards/{id}/copy")
    public ResultVO<Map<String, Object>> copyDashboard(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> source = findDashboard(id);
        if (source == null) return ResultVO.fail("源看板不存在");
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : body;
        String name = firstNonBlank(strOrNull(payload.get("dashboardName")), strOrNull(source.get("dashboardName")) + " 副本");
        Long factoryId = payload.containsKey("factoryId") ? longOrNull(payload.get("factoryId")) : longOrNull(source.get("factoryId"));
        Long ownerUserId = payload.containsKey("ownerUserId") ? longOrNull(payload.get("ownerUserId")) : longOrNull(source.get("ownerUserId"));
        String scopeType = firstNonBlank(strOrNull(payload.get("scopeType")), firstNonBlank(strOrNull(source.get("scopeType")), "FACTORY"));
        int isDefault = intVal(payload.get("isDefault"), 0);
        Long newId = dashboardRepository.copyDashboard(id, name, factoryId, ownerUserId, scopeType, isDefault);
        return dashboardDetail(newId);
    }

    @PutMapping("/dashboards/{id}")
    public ResultVO<Map<String, Object>> updateDashboard(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String name = required(body, "dashboardName", "看板名称不能为空");
        String scopeType = firstNonBlank(strOrNull(body.get("scopeType")), "FACTORY");
        String layoutJson = jsonValue(body.get("layoutJson"), "{\"cols\":12,\"rowHeight\":120}");
        dashboardRepository.updateDashboard(id, name, longOrNull(body.get("factoryId")),
                longOrNull(body.get("ownerUserId")), scopeType, intVal(body.get("isDefault"), 0), layoutJson);
        return dashboardDetail(id);
    }

    @DeleteMapping("/dashboards/{id}")
    public ResultVO<String> deleteDashboard(@PathVariable Long id) {
        dashboardRepository.disableDashboard(id);
        return ResultVO.success("删除成功");
    }

    @GetMapping("/dashboards/{id}/widgets")
    public ResultVO<List<Map<String, Object>>> dashboardWidgets(@PathVariable Long id) {
        return ResultVO.success(listWidgets(id));
    }

    @PostMapping("/widgets")
    public ResultVO<Map<String, Object>> createWidget(@RequestBody Map<String, Object> body) {
        Long dashboardId = requiredLong(body, "dashboardId", "看板不能为空");
        String widgetName = required(body, "widgetName", "图表名称不能为空");
        String widgetType = firstNonBlank(strOrNull(body.get("widgetType")), "KPI").toUpperCase(Locale.ROOT);
        Long id = dashboardRepository.insertWidget(dashboardId, widgetName, widgetType,
                jsonValue(body.get("metricJson"), "{}"),
                jsonValue(body.get("dimensionJson"), "[]"),
                jsonValue(body.get("filterJson"), "{}"),
                jsonValue(body.get("optionJson"), "{}"),
                jsonValue(body.get("layoutJson"), "{\"w\":6,\"h\":2}"),
                intVal(body.get("followGlobalFilter"), 1),
                intVal(body.get("sortOrder"), 0));
        return ResultVO.success(findWidget(id));
    }

    @PutMapping("/widgets/{id}")
    public ResultVO<Map<String, Object>> updateWidget(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String widgetName = required(body, "widgetName", "图表名称不能为空");
        String widgetType = firstNonBlank(strOrNull(body.get("widgetType")), "KPI").toUpperCase(Locale.ROOT);
        dashboardRepository.updateWidget(id, widgetName, widgetType,
                jsonValue(body.get("metricJson"), "{}"),
                jsonValue(body.get("dimensionJson"), "[]"),
                jsonValue(body.get("filterJson"), "{}"),
                jsonValue(body.get("optionJson"), "{}"),
                jsonValue(body.get("layoutJson"), "{\"w\":6,\"h\":2}"),
                intVal(body.get("followGlobalFilter"), 1),
                intVal(body.get("sortOrder"), 0),
                intVal(body.get("isEnabled"), 1));
        return ResultVO.success(findWidget(id));
    }

    @DeleteMapping("/widgets/{id}")
    public ResultVO<String> deleteWidget(@PathVariable Long id) {
        dashboardRepository.disableWidget(id);
        return ResultVO.success("删除成功");
    }

    @PostMapping("/widgets/reorder")
    public ResultVO<String> reorderWidgets(@RequestBody Map<String, Object> body) {
        Object list = body.get("widgets");
        if (list instanceof List) {
            for (Object obj : (List<?>) list) {
                if (!(obj instanceof Map)) continue;
                Map<?, ?> item = (Map<?, ?>) obj;
                Long widgetId = longOrNull(item.get("widgetId"));
                if (widgetId == null) continue;
                dashboardRepository.reorderWidget(widgetId,
                        intVal(item.get("sortOrder"), 0),
                        jsonValue(item.get("layoutJson"), "{\"w\":6,\"h\":2}"));
            }
        }
        return ResultVO.success("保存成功");
    }

    // ==================== P1 日聚合 / 异步聚合任务 ====================


    @GetMapping(value = "/widgets/{id}/export", produces = XLSX_MEDIA_TYPE)
    public ResponseEntity<byte[]> exportWidget(@PathVariable Long id,
                                               @RequestParam(required = false) String startTime,
                                               @RequestParam(required = false) String endTime,
                                               @RequestParam(required = false) Long factoryId,
                                               @RequestParam(required = false) Long lineId,
                                               @RequestParam(required = false) Long workstationId,
                                               @RequestParam(required = false) Long cameraId,
                                               @RequestParam(required = false) Long productId,
                                               @RequestParam(required = false) Long shiftId,
                                               @RequestParam(required = false) Long defectTypeId,
                                               @RequestParam(required = false) String severity,
                                               @RequestParam(required = false) String defectType) throws Exception {
        Map<String, Object> widget = findWidget(id);
        if (widget == null) {
            return ResponseEntity.notFound().build();
        }
        LocalDateTime start = parseTime(startTime, LocalDateTime.of(LocalDate.now(), LocalTime.MIN));
        LocalDateTime end = parseTime(endTime, LocalDateTime.of(LocalDate.now(), LocalTime.MAX.withNano(0)));
        Map<String, Object> data = buildSummary(start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId, defectTypeId, severity, defectType);
        ExportTable table = exportTable(widgetMetric(widget), data);
        String widgetName = String.valueOf(widget.getOrDefault("widgetName", "chart"));
        byte[] bytes = writeWorkbook(widgetName, table.headers, table.rows);
        String filename = sanitizeFileName(widgetName) + "-" + LocalDate.now() + ".xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_MEDIA_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        headers.setContentLength(bytes.length);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }
    @PostMapping("/aggregation/daily/run")
    public ResultVO<Map<String, Object>> runDailyAggregation(@RequestBody(required = false) Map<String, Object> body) {
        aggregationRepository.ensureDailyAggregationTables();
        aggregationRepository.ensureAggregationJobTable();
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : body;
        LocalDate startDate = parseDate(firstNonBlank(strOrNull(payload.get("startDate")), strOrNull(payload.get("startTime"))), LocalDate.now().minusDays(7));
        LocalDate endDate = parseDate(firstNonBlank(strOrNull(payload.get("endDate")), strOrNull(payload.get("endTime"))), LocalDate.now());
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        Long factoryId = longOrNull(payload.get("factoryId"));
        Long lineId = longOrNull(payload.get("lineId"));
        return ResultVO.success(submitDailyAggregation(startDate, endDate, factoryId, lineId, "MANUAL", null));
    }

    @GetMapping("/aggregation/schedule")
    public ResultVO<Map<String, Object>> aggregationSchedule() {
        aggregationRepository.ensureAggregationScheduleTable();
        Map<String, Object> data = findAggregationSchedule();
        // v3 H-1：把「数据新鲜度」一并暴露给运维 —— 改造后查询不再自动重建，
        // 现场若看到水位落后，可据此判断是定时任务没跑还是数据源本身没数据。
        LocalDate watermark = aggregationRepository.latestQualityDay();
        data.put("qualityWatermark", watermark == null ? null : watermark.toString());
        long refreshedAt = lastRecentRefreshAt;
        data.put("lastRecentRefreshAt", refreshedAt == 0 ? null
                : java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(refreshedAt),
                        java.time.ZoneId.systemDefault()).format(DT));
        data.put("recentRefreshIntervalMinutes", RECENT_REFRESH_INTERVAL_MS / 60000);
        data.put("recentRefreshWindowDays", RECENT_REFRESH_WINDOW_DAYS);
        data.put("summaryCacheTtlSeconds", SUMMARY_CACHE_TTL_MS / 1000);
        return ResultVO.success(data);
    }

    @PutMapping("/aggregation/schedule")
    public ResultVO<Map<String, Object>> updateAggregationSchedule(@RequestBody(required = false) Map<String, Object> body) {
        aggregationRepository.ensureAggregationScheduleTable();
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : body;
        int enabled = boolVal(payload.get("enabled"), true) ? 1 : 0;
        String scheduleTime = firstNonBlank(strOrNull(payload.get("scheduleTime")), "02:00");
        LocalTime time = parseScheduleTime(scheduleTime);
        int lookbackDays = Math.max(1, Math.min(365, intVal(payload.get("lookbackDays"), 7)));
        Long factoryId = longOrNull(payload.get("factoryId"));
        Long lineId = longOrNull(payload.get("lineId"));
        aggregationRepository.updateAggregationSchedule(enabled, time.toString(), lookbackDays, factoryId, lineId);
        return ResultVO.success(findAggregationSchedule());
    }

    @GetMapping("/aggregation/jobs")
    public ResultVO<Map<String, Object>> aggregationJobs(@RequestParam(required = false, defaultValue = "1") Integer page,
                                                         @RequestParam(required = false, defaultValue = "10") Integer pageSize,
                                                         @RequestParam(required = false) String status) {
        aggregationRepository.ensureAggregationJobTable();
        int p = Math.max(1, page == null ? 1 : page);
        int size = Math.max(5, Math.min(100, pageSize == null ? 10 : pageSize));
        List<Object> params = new ArrayList<>();
        String where = "";
        if (status != null && !status.trim().isEmpty()) {
            where = " WHERE status = ? ";
            params.add(status.trim().toUpperCase(Locale.ROOT));
        }
        return ResultVO.success(aggregationRepository.queryAggregationJobs(status, p, size));
    }

    @GetMapping("/aggregation/jobs/{id}")
    public ResultVO<Map<String, Object>> aggregationJobDetail(@PathVariable Long id) {
        aggregationRepository.ensureAggregationJobTable();
        Map<String, Object> job = findAggregationJob(id);
        if (job.isEmpty()) return ResultVO.fail("聚合任务不存在");
        return ResultVO.success(job);
    }

    @PostMapping("/aggregation/jobs/{id}/retry")
    public ResultVO<Map<String, Object>> retryAggregationJob(@PathVariable Long id) {
        aggregationRepository.ensureDailyAggregationTables();
        aggregationRepository.ensureAggregationJobTable();
        Map<String, Object> job = findAggregationJob(id);
        if (job.isEmpty()) return ResultVO.fail("聚合任务不存在");
        LocalDate startDate = parseDate(strOrNull(job.get("startDate")), LocalDate.now().minusDays(7));
        LocalDate endDate = parseDate(strOrNull(job.get("endDate")), LocalDate.now());
        return ResultVO.success(submitDailyAggregation(startDate, endDate, longOrNull(job.get("factoryId")), longOrNull(job.get("lineId")), "RETRY", id));
    }

    @GetMapping("/aggregation/jobs/recent")
    public ResultVO<List<Map<String, Object>>> recentAggregationJobs() {
        aggregationRepository.ensureAggregationJobTable();
        return ResultVO.success(aggregationRepository.queryRecentAggregationJobs());
    }

    /**
     * 每日聚合调度检查（每分钟看一眼是否到点）。
     *
     * <p><b>NEW-01（2026-11-06 v5 评审）：<code>lastRunDate</code> 不再在提交后立即写。</b>
     * 改造前是「submit（异步）→ 立刻 updateLastRun(today)」，
     * 任务在后台 FAILED 时 lastRunDate 已经是今天 → <b>当天不会再重试</b>，
     * 而 H-1 已拆掉「查询时同步重建」这条自愈通道，一次瞬时故障就是当天数据永久缺口。
     *
     * <p>现在：由 {@link #submitDailyAggregation} 在任务<b>成功后</b>回写 lastRunDate；
     * 失败则保持为空，下一分钟自然重试 —— 但加 10 分钟节流，
     * 避免一个持续失败的任务每分钟都把整条单线程执行器占住。
     *
     * <p>另：原 {@code catch (Exception ignored)} 会把「表不存在」「口令失效」这类
     * 调度侧故障完全吞掉，本轮改为 ERROR 级日志（这是排查当日数据缺口的唯一线索）。
     */
    @Scheduled(fixedDelay = 60000)
    public void runScheduledDailyAggregationIfNeeded() {
        try {
            aggregationRepository.ensureAggregationScheduleTable();
            Map<String, Object> cfg = findAggregationSchedule();
            if (!boolVal(cfg.get("enabled"), true)) return;
            LocalTime scheduleTime = parseScheduleTime(strOrNull(cfg.get("scheduleTime")));
            LocalDate today = LocalDate.now();
            String lastRunDate = strOrNull(cfg.get("lastRunDate"));
            if (LocalTime.now().isBefore(scheduleTime) || today.toString().equals(lastRunDate)) return;
            // 失败重试节流：成功后 lastRunDate 已写入，此处只对「还没成功」的情况生效
            long now = System.currentTimeMillis();
            long lastSubmit = lastScheduledSubmitAt.get();
            if (now - lastSubmit < SCHEDULED_RETRY_INTERVAL_MS) return;
            if (!lastScheduledSubmitAt.compareAndSet(lastSubmit, now)) return;
            int lookbackDays = Math.max(1, Math.min(365, intVal(cfg.get("lookbackDays"), 7)));
            LocalDate startDate = today.minusDays(lookbackDays - 1L);
            submitDailyAggregation(startDate, today, longOrNull(cfg.get("factoryId")), longOrNull(cfg.get("lineId")), "SCHEDULE", null);
        } catch (Exception e) {
            // 定时检查本身不影响主服务，但必须留痕：这是当日数据缺口的唯一线索
            log.error("日聚合调度检查失败（今日可能没有聚合数据）: {}", e.getMessage(), e);
        }
    }

    // ==================== v3 H-1：聚合数据新鲜度（读路径只读，写库只发生在任务里） ====================

    /**
     * 近期窗口自动刷新（PERF-02 / v3 H-1，2026-11-06）。
     *
     * <p><b>为什么必须有它</b>：H-1 把「查询时同步重建」拿掉之后，日聚合表的新鲜度
     * 就只能由任务保证。原有 02:00 的日任务只跑一次，白天查询的「今天」全是空档 ——
     * 因此这里补一个 5 分钟粒度的近期窗口刷新。
     *
     * <p><b>为什么只刷近 3 天</b>：{@code shouldUseDailyAggregation} 的阈值是 3 天，
     * 刷这 3 天已能覆盖「今天/昨天/近三天」这类高频看板查询；更早的历史区间属于
     * 回溯分析，走显式管理端点 {@code POST /aggregation/daily/run}（可指定任意区间），
     * 不能让一个定时任务每天去重建几个月的数据。
     *
     * <p><b>为什么不在任务表里留记录</b>：5 分钟一次 = 288 条/天，会把
     * {@code analytics_aggregation_job} 变成日志表，运维真正要找的手工/失败任务
     * 被淹没。这里只写应用日志；手工/失败任务仍然完整留痕。
     *
     * <p>并发保护：单线程执行器 + {@code recentRefreshInFlight} 标志，
     * 上一轮没跑完（数据量大或夜间日任务占着线程）时本轮直接跳过。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void refreshRecentDailyAggregation() {
        if (recentRefreshInFlight.get()) {
            return;
        }
        final LocalDate today = LocalDate.now();
        final LocalDate startDate = today.minusDays(RECENT_REFRESH_WINDOW_DAYS - 1L);
        if (!recentRefreshInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            aggregationRepository.ensureDailyAggregationTables();
        } catch (Exception e) {
            recentRefreshInFlight.set(false);
            log.warn("日聚合近期刷新前置检查失败: {}", e.getMessage());
            return;
        }
        aggregationExecutor.submit(() -> {
            long begin = System.currentTimeMillis();
            try {
                int rows = rebuildDailyAtomically(startDate, today, null, null);
                purgeOrphanAggregationRowsIfDue();
                lastRecentRefreshAt = System.currentTimeMillis();
                log.info("日聚合近期窗口刷新完成: {} ~ {}，写入 {} 行，耗时 {} ms",
                        startDate, today, rows, System.currentTimeMillis() - begin);
            } catch (Exception e) {
                log.warn("日聚合近期窗口刷新失败: {} ~ {}，{}", startDate, today, e.getMessage());
            } finally {
                recentRefreshInFlight.set(false);
            }
        });
    }

    /**
     * 把「质量 + 缺陷类型」两张日聚合表的重建放进**一个短事务**。
     *
     * <p>原实现 DELETE 与 INSERT 是两条独立语句、各自自动提交 —— 中间窗口内查询会看到
     * 「今天的数据被删了还没插回来」（表现为看板数字突然掉 0）。放进同一事务后，
     * InnoDB 的 MVCC 让并发查询要么看到旧版本、要么看到新版本，不会看到空窗。
     *
     * <p>注意：事务里只有两条 SQL（INSERT ... SELECT 由 MySQL 内部完成），
     * 不涉及任何文件 IO / 解码，符合「短事务」要求。
     */
    private int rebuildDailyAtomically(LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId) {
        Integer rows = txTemplate().execute(status -> {
            int quality = rebuildQualityDaily(startDate, endDate, factoryId, lineId);
            int defect = rebuildDefectDaily(startDate, endDate, factoryId, lineId);
            return quality + defect;
        });
        return rows == null ? 0 : rows;
    }

    /**
     * 查询侧发现聚合落后 → 异步补齐（10 分钟节流）。
     *
     * <p>只提交、不等待：本次请求立刻返回已物化结果 + {@code aggregationStale=true}，
     * 前端据此提示「数据补齐中」。相比改造前每次查询都同步重建，代价从
     * 「每请求一次全范围写」降到「每 10 分钟最多一次」。
     */
    private boolean requestAsyncAggregation(LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId) {
        long now = System.currentTimeMillis();
        long last = lastStaleTriggerAt.get();
        if (now - last < STALE_TRIGGER_MIN_INTERVAL_MS) {
            return false;
        }
        if (!lastStaleTriggerAt.compareAndSet(last, now)) {
            return false;
        }
        try {
            submitDailyAggregation(startDate, endDate, factoryId, lineId, "AUTO_STALE", null);
            log.info("检测到日聚合落后（水位不足），已提交后台补齐任务: {} ~ {}", startDate, endDate);
            return true;
        } catch (Exception e) {
            log.warn("提交日聚合补齐任务失败（不影响本次查询）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 孤儿聚合行清理（产线/工厂已删除但聚合表残留）—— 每小时最多一次。
     *
     * <p>改造前这一步跟着每次报表查询跑：4 张表全表 DELETE + LEFT JOIN，
     * 是最重的写操作之一，却挂在读路径上。现在只有「近期刷新任务」会触发它，
     * 且 1 小时节流 —— 产线删除是低频人工操作，1 小时延迟完全可接受。
     */
    private void purgeOrphanAggregationRowsIfDue() {
        long now = System.currentTimeMillis();
        long last = lastOrphanPurgeAt.get();
        if (now - last < ORPHAN_PURGE_MIN_INTERVAL_MS) {
            return;
        }
        if (!lastOrphanPurgeAt.compareAndSet(last, now)) {
            return;
        }
        try {
            int purged = aggregationRepository.purgeOrphanAggregationRows();
            if (purged > 0) {
                log.info("已清理孤儿聚合行 {} 条（产线已删除但聚合表残留）", purged);
            }
        } catch (Exception e) {
            log.warn("清理孤儿聚合行失败（不影响统计结果）: {}", e.getMessage());
        }
    }

    private Map<String, Object> submitDailyAggregation(LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId, String triggerSource, Long retryFromJobId) {
        // ★ 必须先确保两张日聚合表存在。
        //   之前只有 runDailyAggregation / retryAggregationJob 两条入口做了 ensure，
        //   而 @Scheduled 定时任务走的 submitDailyAggregation 直接就 DELETE，
        //   线上实测报 "Table 'wms-new.agg_quality_day' doesn't exist" → 任务 FAILED。
        aggregationRepository.ensureDailyAggregationTables();
        aggregationRepository.ensureAggregationJobTable();
        Long jobId = createAggregationJob(startDate, endDate, factoryId, lineId, triggerSource, retryFromJobId);
        Map<String, Object> job = findAggregationJob(jobId);
        aggregationExecutor.submit(() -> {
            try {
                int qualityRows = rebuildQualityDaily(startDate, endDate, factoryId, lineId);
                int defectRows = rebuildDefectDaily(startDate, endDate, factoryId, lineId);
                updateAggregationJob(jobId, "SUCCESS", qualityRows, defectRows, "日聚合完成", null);
                // NEW-01：只有【定时任务】且【真正成功】才把「今天跑过了」写进调度表。
                // 写在提交之后（改造前的做法）会把失败也记成已完成，导致当天不再重试。
                if ("SCHEDULE".equals(triggerSource)) {
                    try {
                        aggregationRepository.updateLastRun(endDate, jobId);
                    } catch (Exception e) {
                        // 回写失败只影响「是否会重复跑一次」，不影响数据本身
                        log.warn("回写日聚合 lastRunDate 失败: jobId={}, {}", jobId, e.getMessage());
                    }
                }
            } catch (Exception e) {
                String detail = stackTraceText(e);
                updateAggregationJob(jobId, "FAILED", 0, 0, clip(e.getMessage(), 500), detail);
                // NEW-01：失败必须留 ERROR 痕，否则「今天数据没了」在日志里查不到原因
                log.error("日聚合任务失败: jobId={}, {} ~ {}, trigger={}, 原因: {}",
                        jobId, startDate, endDate, triggerSource, e.getMessage(), e);
            }
        });
        return job;
    }

    /**
     * 三个建表动作的「已完成」标志。
     *
     * <p><b>2026-10-31 服务器实测发现的问题</b>：
     * 这三个 {@code ensure*} 方法原本<b>每次被调用都执行一遍 DDL</b>
     * （{@code CREATE TABLE IF NOT EXISTS ...}），
     * 而它们被 {@code @Scheduled(fixedDelay = 60000)} 的定时任务每分钟调一次，
     * 于是<b>每分钟产生 4 条 DDL</b>（schedule + job + 2 张 agg 表）。
     *
     * <p>实测证据（服务器 MySQL 慢查询日志 {@code /var/log/mysql/slow.log}）：
     * <pre>
     * # Time: 2026-11-01T20:16:24.300856Z
     * # Query_time: 7.411144  Lock_time: 0.000003
     * CREATE TABLE IF NOT EXISTS analytics_aggregation_schedule ...
     * </pre>
     *
     * <p>危害：
     * <ul>
     *   <li>DDL 会拿 MySQL 的**元数据锁（MDL）**，表越大锁的时间越长，
     *       期间该表上的其它查询全部排队 —— 实测单次最慢 <b>7.4 秒</b>；</li>
     *   <li>DDL 不可回滚、不能与事务共存，是 InnoDB 里最重的操作之一；</li>
     *   <li>每分钟 4 次 = 每天 5760 次无谓 DDL，纯属自伤；</li>
     *   <li>慢查询日志被自己的 DDL 淹没，真正的业务慢查找不到。</li>
     * </ul>
     *
     * <p>修复：DDL <b>只在表真正缺失时才执行</b>。
     * 判断依据从「进程内标志位」改成 <b>INFORMATION_SCHEMA 实际存在性</b>：
     * <ul>
     *   <li>标志位只能反映「本进程建过」，一旦表被人为删掉（或首次调用时建表失败）
     *       就永远不会再建 —— 实测线上就出现过
     *       {@code agg_quality_day} 表缺失导致日聚合任务 FAILED；</li>
     *   <li>INFORMATION_SCHEMA 查询是内存字典查询（毫秒级），
     *       比 DDL 轻两个数量级，不构成性能问题。</li>
     * </ul>
     */
    // DDD P5：原 3 个 AtomicBoolean ensured 标志位为死代码（只 set 从不 get 短路），
    // 已随请求路径惰性建表收敛而删除 —— 启动期由 SchemaInitializer 统一保证表结构，
    // repository.ensure* 内部自带 INFORMATION_SCHEMA 幂等判断。

    private Map<String, Object> findAggregationSchedule() {
        aggregationRepository.ensureAggregationScheduleTable();
        Map<String, Object> row = aggregationRepository.findAggregationSchedule();
        row.put("enabled", boolVal(row.get("enabled"), true));
        return row;
    }

    private Long createAggregationJob(LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId, String triggerSource, Long retryFromJobId) {
        return aggregationRepository.createAggregationJob(startDate, endDate, factoryId, lineId, triggerSource, retryFromJobId);
    }

    private void updateAggregationJob(Long jobId, String status, int qualityRows, int defectRows, String message, String errorDetail) {
        try {
            aggregationRepository.ensureAggregationJobTable();
            aggregationRepository.updateAggregationJob(jobId, status, qualityRows, defectRows, message, errorDetail);
        } catch (Exception e) {
            // 不向外抛（聚合线程里抛出去也没人接），但必须留痕：
            // 这里写失败意味着任务状态永远停在 RUNNING，DELETE 掉这条日志等于放弃现场唯一线索。
            log.error("更新聚合任务状态失败: jobId={}, status={}, {}", jobId, status, e.getMessage(), e);
        }
    }

    private Map<String, Object> findAggregationJob(Long jobId) {
        return aggregationRepository.findAggregationJob(jobId);
    }
    private int rebuildQualityDaily(LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId) {
        List<Object> deleteParams = new ArrayList<>();
        String deleteWhere = buildDailyDeleteWhere("date_bucket", startDate, endDate, factoryId, lineId, deleteParams);
        List<Object> insertParams = new ArrayList<>();
        String sourceWhere = buildDailySourceWhere("time_bucket", startDate, endDate, factoryId, lineId, insertParams);
        return aggregationRepository.rebuildQualityDaily(deleteWhere, deleteParams, sourceWhere, insertParams);
    }

    private int rebuildDefectDaily(LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId) {
        List<Object> deleteParams = new ArrayList<>();
        String deleteWhere = buildDailyDeleteWhere("date_bucket", startDate, endDate, factoryId, lineId, deleteParams);
        List<Object> insertParams = new ArrayList<>();
        String sourceWhere = buildDailySourceWhere("time_bucket", startDate, endDate, factoryId, lineId, insertParams);
        return aggregationRepository.rebuildDefectDaily(deleteWhere, deleteParams, sourceWhere, insertParams);
    }

    private String buildDailyDeleteWhere(String column, LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE " + column + " BETWEEN ? AND ? ");
        params.add(java.sql.Date.valueOf(startDate));
        params.add(java.sql.Date.valueOf(endDate));
        add(where, params, "factory_id", factoryId);
        add(where, params, "line_id", lineId);
        return where.toString();
    }

    private String buildDailySourceWhere(String column, LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE " + column + " >= ? AND " + column + " < ? ");
        params.add(Timestamp.valueOf(startDate.atStartOfDay()));
        params.add(Timestamp.valueOf(endDate.plusDays(1).atStartOfDay()));
        add(where, params, "factory_id", factoryId);
        add(where, params, "line_id", lineId);
        return where.toString();
    }

    private List<Map<String, Object>> buildDefectPivot(String defectTable, String timeColumn, boolean daily,
                                                       LocalDateTime start, LocalDateTime end,
                                                       Long factoryId, Long lineId, Long workstationId,
                                                       Long cameraId, Long productId, Long shiftId) {
        List<Object> params = new ArrayList<>();
        String where = buildDefectWhere(timeColumn, daily, start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId, null, null, params);
        return queryRepository.queryDefectPivot(defectTable, where, params);
    }

    private String buildQualityWhere(String timeColumn, boolean daily, LocalDateTime start, LocalDateTime end, Long factoryId, Long lineId, Long workstationId,
                                     Long cameraId, Long productId, Long shiftId, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE " + timeColumn + " BETWEEN ? AND ? ");
        addTimeParams(daily, start, end, params);
        add(where, params, "factory_id", factoryId);
        add(where, params, "line_id", lineId);
        add(where, params, "workstation_id", workstationId);
        add(where, params, "camera_id", cameraId);
        add(where, params, "product_id", productId);
        add(where, params, "shift_id", shiftId);
        return where.toString();
    }

    private String buildQualityWhereAlias(String alias, String timeColumn, boolean daily, LocalDateTime start, LocalDateTime end, Long factoryId, Long lineId, Long workstationId,
                                          Long cameraId, Long productId, Long shiftId, List<Object> params) {
        String prefix = alias + ".";
        StringBuilder where = new StringBuilder(" WHERE " + prefix + timeColumn + " BETWEEN ? AND ? ");
        addTimeParams(daily, start, end, params);
        add(where, params, prefix + "factory_id", factoryId);
        add(where, params, prefix + "line_id", lineId);
        add(where, params, prefix + "workstation_id", workstationId);
        add(where, params, prefix + "camera_id", cameraId);
        add(where, params, prefix + "product_id", productId);
        add(where, params, prefix + "shift_id", shiftId);
        return where.toString();
    }

    private String buildDefectWhere(String timeColumn, boolean daily, LocalDateTime start, LocalDateTime end, Long factoryId, Long lineId, Long workstationId,
                                    Long cameraId, Long productId, Long shiftId, Long defectTypeId, String severity, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE " + timeColumn + " BETWEEN ? AND ? ");
        addTimeParams(daily, start, end, params);
        add(where, params, "factory_id", factoryId);
        add(where, params, "line_id", lineId);
        add(where, params, "workstation_id", workstationId);
        add(where, params, "camera_id", cameraId);
        add(where, params, "product_id", productId);
        add(where, params, "shift_id", shiftId);
        add(where, params, "defect_type_id", defectTypeId);
        if (severity != null && !severity.trim().isEmpty()) {
            where.append(" AND severity = ? ");
            params.add(severity.trim().toUpperCase(Locale.ROOT));
        }
        return where.toString();
    }

    private void addTimeParams(boolean daily, LocalDateTime start, LocalDateTime end, List<Object> params) {
        if (daily) {
            params.add(java.sql.Date.valueOf(start.toLocalDate()));
            params.add(java.sql.Date.valueOf(end.toLocalDate()));
        } else {
            params.add(Timestamp.valueOf(start));
            params.add(Timestamp.valueOf(end));
        }
    }

    private boolean shouldUseDailyAggregation(LocalDateTime start, LocalDateTime end) {
        long days = ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) + 1;
        return days >= DAILY_AGGREGATION_THRESHOLD_DAYS;
    }
    private void add(StringBuilder where, List<Object> params, String column, Long value) {
        if (value != null) {
            where.append(" AND ").append(column).append(" = ? ");
            params.add(value);
        }
    }

    private LocalDateTime parseTime(String value, LocalDateTime defaultValue) {
        if (value == null || value.trim().isEmpty()) return defaultValue;
        try { return LocalDateTime.parse(value.trim(), DT); }
        catch (Exception e) { return LocalDateTime.parse(value.trim()); }
    }

    private LocalDate parseDate(String value, LocalDate defaultValue) {
        if (value == null || value.trim().isEmpty()) return defaultValue;
        String text = value.trim();
        if (text.length() >= 10) text = text.substring(0, 10);
        try {
            return LocalDate.parse(text);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private LocalTime parseScheduleTime(String value) {
        String text = firstNonBlank(value, "02:00").trim();
        try {
            if (text.length() == 5) text = text + ":00";
            return LocalTime.parse(text);
        } catch (Exception e) {
            return LocalTime.of(2, 0);
        }
    }

    private void addColumnQuiet(String tableName, String columnName, String ddl) {
        aggregationRepository.addColumnQuiet(tableName, columnName, ddl);
    }

    private String stackTraceText(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName()).append(": ").append(e.getMessage() == null ? "" : e.getMessage());
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append("\n  at ").append(element.toString());
            if (sb.length() > 3800) break;
        }
        return sb.toString();
    }

    private List<Map<String, Object>> safeList(String sql) {
        return queryRepository.safeList(sql);
    }

    private Map<String, Object> one(String sql, List<Object> params) {
        return queryRepository.one(sql, params);
    }

    private Map<String, Object> findDefaultDashboard(Long factoryId) {
        return lineAnalyticsRepository.findDefaultDashboard(factoryId);
    }

    private Map<String, Object> findDashboard(Long id) {
        return lineAnalyticsRepository.findDashboard(id);
    }

    private Map<String, Object> findWidget(Long id) {
        return lineAnalyticsRepository.findWidget(id);
    }

    private List<Map<String, Object>> listWidgets(Long dashboardId) {
        return lineAnalyticsRepository.listWidgets(dashboardId);
    }

    /**
     * 默认看板 —— **工厂厂长视角**（2026-11-02 设计）。
     *
     * <p><b>设计主线：质量总览 → 缺陷帕累托 → 责任工位定位 → 严重度构成 → 缺陷趋势</b>
     *
     * <p>为什么是这条主线而不是「良率 + 产量」：
     * <ul>
     *   <li>现场设备走的是缺陷图片上报通道，<b>只上传缺陷图，没有良品样本</b>，
     *       良率在当前数据源下<b>根本算不出来</b>；</li>
     *   <li>厂长的决策链是「质量好不好 → 主要矛盾是什么 → 谁的工位 → 多严重 → 趋势如何」，
     *       帕累托与责任工位正好对应「主要矛盾」和「责任人」这两个动作；</li>
     *   <li>等设备端补齐良品样本后，产量/良率维度可以再加回来，不影响本主线。</li>
     * </ul>
     */
    private Long createDefaultDashboard(Long factoryId) {
        Long dashboardId = dashboardRepository.insertDefaultDashboard(factoryId);
        // 1. 质量总览：一眼看清规模、风险等级、首要缺陷
        insertDefaultWidget(dashboardId, "质量总览（厂长视角）", "KPI", "{\"metric\":\"plantOverview\"}", "[\"time\"]", "{\"x\":0,\"y\":0,\"w\":12,\"h\":1}", 1);
        // 2. 缺陷帕累托：80/20 定位主要矛盾
        insertDefaultWidget(dashboardId, "缺陷类型帕累托（累计占比）", "BAR", "{\"metric\":\"defectPareto\"}", "[\"defectType\"]", "{\"x\":0,\"y\":1,\"w\":8,\"h\":3}", 2);
        // 3. 严重度构成：质量风险等级
        insertDefaultWidget(dashboardId, "缺陷严重度构成", "STACK_BAR", "{\"metric\":\"severityDistribution\"}", "[\"severity\"]", "{\"x\":8,\"y\":1,\"w\":4,\"h\":3}", 3);
        // 4. 责任工位：找问题出在哪个工位
        insertDefaultWidget(dashboardId, "责任工位定位（缺陷数）", "BAR", "{\"metric\":\"stationRanking\"}", "[\"workstation\"]", "{\"x\":0,\"y\":4,\"w\":6,\"h\":3}", 4);
        // 5. 责任相机：排查设备或视角问题
        insertDefaultWidget(dashboardId, "责任相机定位（缺陷数）", "BAR", "{\"metric\":\"cameraRanking\"}", "[\"camera\"]", "{\"x\":6,\"y\":4,\"w\":6,\"h\":3}", 5);
        // 6. 缺陷趋势：看问题是在收敛还是在恶化
        insertDefaultWidget(dashboardId, "缺陷发生趋势", "LINE", "{\"metric\":\"defectTrend\"}", "[\"time\"]", "{\"x\":0,\"y\":7,\"w\":12,\"h\":3}", 6);
        return dashboardId;
    }

    /**
     * 已存在的旧默认看板（老名字 + 老组件）升级为厂长视角。
     *
     * <p>只做一次：把「默认产线统计看板」重命名并按新蓝图重建组件。
     * 若用户已自行改造过该看板（有非内置组件），则跳过，避免覆盖用户配置。
     */
    private void upgradeLegacyDefaultDashboard(Long factoryId) {
        try {
            List<Map<String, Object>> legacy = dashboardRepository.findLegacyDefaultDashboard(factoryId);
            if (legacy.isEmpty()) return;
            Long dashboardId = longVal(legacy.get(0).get("dashboardId"));
            // 只升级「组件全是内置默认组件」的看板，保留用户手工改造过的
            Integer customCount = dashboardRepository.countCustomWidgets(dashboardId);
            if (customCount != null && customCount > 0) return;
            dashboardRepository.renameDashboard(dashboardId);
            dashboardRepository.deleteWidgets(dashboardId);
            createDashboardWidgets(dashboardId);
        } catch (Exception ignored) {
            // 升级失败不影响主流程，用户仍可手工新建看板
        }
    }

    /** 按厂长视角蓝图写入组件（新建与升级共用）。 */
    private void createDashboardWidgets(Long dashboardId) {
        insertDefaultWidget(dashboardId, "质量总览（厂长视角）", "KPI", "{\"metric\":\"plantOverview\"}", "[\"time\"]", "{\"x\":0,\"y\":0,\"w\":12,\"h\":1}", 1);
        insertDefaultWidget(dashboardId, "缺陷类型帕累托（累计占比）", "BAR", "{\"metric\":\"defectPareto\"}", "[\"defectType\"]", "{\"x\":0,\"y\":1,\"w\":8,\"h\":3}", 2);
        insertDefaultWidget(dashboardId, "缺陷严重度构成", "STACK_BAR", "{\"metric\":\"severityDistribution\"}", "[\"severity\"]", "{\"x\":8,\"y\":1,\"w\":4,\"h\":3}", 3);
        insertDefaultWidget(dashboardId, "责任工位定位（缺陷数）", "BAR", "{\"metric\":\"stationRanking\"}", "[\"workstation\"]", "{\"x\":0,\"y\":4,\"w\":6,\"h\":3}", 4);
        insertDefaultWidget(dashboardId, "责任相机定位（缺陷数）", "BAR", "{\"metric\":\"cameraRanking\"}", "[\"camera\"]", "{\"x\":6,\"y\":4,\"w\":6,\"h\":3}", 5);
        insertDefaultWidget(dashboardId, "缺陷发生趋势", "LINE", "{\"metric\":\"defectTrend\"}", "[\"time\"]", "{\"x\":0,\"y\":7,\"w\":12,\"h\":3}", 6);
    }

    private void insertDefaultWidget(Long dashboardId, String name, String type, String metricJson,
                                     String dimensionJson, String layoutJson, int sortOrder) {
        dashboardRepository.insertDefaultWidget(dashboardId, name, type, metricJson, dimensionJson, layoutJson, sortOrder);
    }


    private ExportTable exportTable(String metric, Map<String, Object> summary) {
        ExportTable table = new ExportTable();
        if ("quality".equals(metric)) {
            table.headers = list("指标", "数值");
            Map<String, Object> kpi = map(summary.get("kpi"));
            table.rows.add(row("检测总数", kpi.get("totalCount")));
            table.rows.add(row("OK数", kpi.get("okCount")));
            table.rows.add(row("NG数", kpi.get("ngCount")));
            table.rows.add(row("缺陷数", kpi.get("defectCount")));
            table.rows.add(row("良率", kpi.get("yieldRate")));
            table.rows.add(row("NG率", kpi.get("ngRate")));
        } else if ("trend".equals(metric)) {
            table.headers = list("时间", "检测总数", "OK数", "NG数", "良率");
            for (Map<String, Object> r : listMap(summary.get("trend"))) {
                table.rows.add(row(r.get("timeBucket"), r.get("totalCount"), r.get("okCount"), r.get("ngCount"), r.get("yieldRate")));
            }
        } else if ("stationRanking".equals(metric)) {
            table.headers = list("工位", "检测总数", "OK数", "NG数", "良率");
            for (Map<String, Object> r : listMap(summary.get("stationRanking"))) {
                table.rows.add(row(r.get("workstationName"), r.get("totalCount"), r.get("okCount"), r.get("ngCount"), r.get("yieldRate")));
            }
        } else if ("cameraRanking".equals(metric)) {
            table.headers = list("相机", "相机编号", "检测总数", "OK数", "NG数", "良率");
            for (Map<String, Object> r : listMap(summary.get("cameraRanking"))) {
                table.rows.add(row(r.get("cameraName"), r.get("cameraCode"), r.get("totalCount"), r.get("okCount"), r.get("ngCount"), r.get("yieldRate")));
            }
        } else if ("defectPivot".equals(metric)) {
            table.headers = list("产品ID", "缺陷ID", "缺陷编码", "缺陷名称", "缺陷数");
            for (Map<String, Object> r : listMap(summary.get("defectPivot"))) {
                table.rows.add(row(r.get("productId"), r.get("defectTypeId"), r.get("defectCode"), r.get("defectName"), r.get("defectCount")));
            }
        } else if ("mappingExceptions".equals(metric)) {
            table.headers = list("时间", "相机编号", "产品", "上报编码", "异常类型", "失败原因");
            for (Map<String, Object> r : listMap(summary.get("mappingExceptions"))) {
                table.rows.add(row(r.get("createTime"), r.get("cameraCode"), r.get("productName"), r.get("sourceCode"), r.get("exceptionType"), r.get("exceptionMessage")));
            }
        } else if ("plantOverview".equals(metric)) {
            table.headers = list("指标", "数值");
            Map<String, Object> o = map(summary.get("plantOverview"));
            table.rows.add(row("检测总量", o.get("detectionTotal")));
            table.rows.add(row("缺陷总数", o.get("defectTotal")));
            table.rows.add(row("高危缺陷数", o.get("highDefect")));
            table.rows.add(row("高危占比(%)", o.get("highRate")));
            table.rows.add(row("单图缺陷数", o.get("defectPerImage")));
            table.rows.add(row("受影响工位数", o.get("affectedWorkstation")));
            table.rows.add(row("涉及相机数", o.get("affectedCamera")));
            table.rows.add(row("首要缺陷", o.get("topDefectName")));
            table.rows.add(row("首要缺陷占比(%)", o.get("topDefectRate")));
            table.rows.add(row("良率(%)", o.get("yieldRateAvailable") == Boolean.FALSE ? "不可计算（无良品样本）" : o.get("yieldRate")));
        } else if ("defectPareto".equals(metric)) {
            table.headers = list("缺陷类型", "严重度", "缺陷数", "累计缺陷数", "累计占比(%)");
            for (Map<String, Object> r : listMap(summary.get("defectPareto"))) {
                table.rows.add(row(r.get("defectName"), r.get("severity"), r.get("defectCount"), r.get("accumulatedDefect"), r.get("accumulatedRate")));
            }
        } else if ("severityDistribution".equals(metric)) {
            table.headers = list("严重度", "缺陷数");
            for (Map<String, Object> r : listMap(summary.get("severityDistribution"))) {
                table.rows.add(row(r.get("severity"), r.get("defectCount")));
            }
        } else if ("defectTrend".equals(metric)) {
            table.headers = list("时间", "缺陷数", "异常数");
            for (Map<String, Object> r : listMap(summary.get("defectTrend"))) {
                table.rows.add(row(r.get("timeBucket"), r.get("defectCount"), r.get("ngCount")));
            }
        } else {
            table.headers = list("缺陷ID", "缺陷编码", "缺陷名称", "严重度", "缺陷数");
            for (Map<String, Object> r : listMap(summary.get("defectRanking"))) {
                table.rows.add(row(r.get("defectTypeId"), r.get("defectCode"), r.get("defectName"), r.get("severity"), r.get("defectCount")));
            }
        }
        return table;
    }

    private byte[] writeWorkbook(String sheetName, List<String> headers, List<List<Object>> rows) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(clip(sanitizeSheetName(sheetName), 31));
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<Object> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    Cell cell = row.createCell(c);
                    Object value = values.get(c);
                    if (value instanceof Number) cell.setCellValue(((Number) value).doubleValue());
                    else cell.setCellValue(value == null ? "" : String.valueOf(value));
                }
            }
            for (int i = 0; i < Math.min(headers.size(), 12); i++) sheet.autoSizeColumn(i);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private String widgetMetric(Map<String, Object> widget) {
        Map<String, Object> metricJson = parseJsonMap(widget.get("metricJson"));
        String metric = strOrNull(metricJson.get("metric"));
        if (metric != null && !metric.trim().isEmpty()) return metric.trim();
        String type = strOrNull(widget.get("widgetType"));
        if ("KPI".equalsIgnoreCase(type)) return "quality";
        if ("LINE".equalsIgnoreCase(type)) return "trend";
        if ("BAR".equalsIgnoreCase(type)) return "stationRanking";
        if ("STACK_BAR".equalsIgnoreCase(type)) return "stackDefectBySeverity";
        if ("PIVOT".equalsIgnoreCase(type)) return "defectPivot";
        if ("PLANT_OVERVIEW".equalsIgnoreCase(type)) return "plantOverview";
        if ("PARETO".equalsIgnoreCase(type)) return "defectPareto";
        if ("SEVERITY".equalsIgnoreCase(type)) return "severityDistribution";
        return "defectRanking";
    }

    private Map<String, Object> parseJsonMap(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map) return map(value);
        try {
            return objectMapper.readValue(String.valueOf(value), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map) return (Map<String, Object>) value;
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listMap(Object value) {
        if (value instanceof List) return (List<Map<String, Object>>) value;
        return new ArrayList<>();
    }

    private List<String> list(String... values) {
        List<String> list = new ArrayList<>();
        Collections.addAll(list, values);
        return list;
    }

    private List<Object> row(Object... values) {
        List<Object> row = new ArrayList<>();
        Collections.addAll(row, values);
        return row;
    }

    private String sanitizeFileName(String value) {
        String s = value == null ? "chart" : value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "chart" : s;
    }

    private String sanitizeSheetName(String value) {
        String s = sanitizeFileName(value).replaceAll("[\\[\\]]", "_");
        return s.isEmpty() ? "data" : s;
    }

    private String clip(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
    private Map<String, Object> item(String code, String name, String desc) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("name", name);
        item.put("description", desc);
        return item;
    }

    private String required(Map<String, Object> body, String key, String message) {
        String value = strOrNull(body.get(key));
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private Long requiredLong(Map<String, Object> body, String key, String message) {
        Long value = longOrNull(body.get(key));
        if (value == null || value <= 0) throw new IllegalArgumentException(message);
        return value;
    }

    private String firstNonBlank(String a, String b) {
        return a == null || a.trim().isEmpty() ? b : a.trim();
    }

    private int intVal(Object o, int defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Number) return ((Number) o).intValue();
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? defaultValue : Integer.parseInt(s);
    }

    private boolean boolVal(Object o, boolean defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return defaultValue;
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    private String jsonValue(Object value, String defaultJson) {
        if (value == null) return defaultJson;
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? defaultJson : text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return defaultJson;
        }
    }

    private String strOrNull(Object o) { return o == null ? null : String.valueOf(o); }
    private Long longVal(Object o) {
        Long value = longOrNull(o);
        return value == null ? 0L : value;
    }
    private Long longOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : Long.parseLong(s);
    }

    private static class ExportTable {
        List<String> headers = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
    }
}
