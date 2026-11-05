package com.wms.analytics.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.analytics.infra.repository.AnalyticsAggregationRepository;
import com.wms.analytics.infra.repository.AnalyticsDashboardRepository;
import com.wms.analytics.infra.repository.LineAnalyticsQueryRepository;
import com.wms.analytics.infra.repository.LineAnalyticsRepository;
import com.wms.shared.common.ResultVO;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final String XLSX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService aggregationExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "analytics-daily-aggregation");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong aggregationJobSeq = new AtomicLong(1000);
    private final Map<Long, Map<String, Object>> aggregationJobs = new ConcurrentHashMap<>();

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
                                                     @RequestParam(required = false) String severity) {
        LocalDateTime start = parseTime(startTime, LocalDateTime.of(LocalDate.now(), LocalTime.MIN));
        LocalDateTime end = parseTime(endTime, LocalDateTime.of(LocalDate.now(), LocalTime.MAX.withNano(0)));
        return ResultVO.success(buildSummary(start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId, defectTypeId, severity));
    }

    private Map<String, Object> buildSummary(LocalDateTime start, LocalDateTime end,
                                             Long factoryId, Long lineId, Long workstationId, Long cameraId,
                                             Long productId, Long shiftId, Long defectTypeId, String severity) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("结束时间不能早于开始时间");
        }
        boolean daily = shouldUseDailyAggregation(start, end);
        if (daily) {
            ensureDailyAggregationTables();
            // 大范围查询直接使用日聚合表；若当前时间范围尚未聚合，查询前同步补齐一次。
            rebuildQualityDaily(start.toLocalDate(), end.toLocalDate(), factoryId, lineId);
            rebuildDefectDaily(start.toLocalDate(), end.toLocalDate(), factoryId, lineId);
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

        // ★ 数据源兜底：聚合表无数据时，回落到「缺陷图片事实表」。
        //   现场设备走的是 defect-images/upload 通道，该通道只写 defect_image_*，
        //   不写 agg_* 聚合表 —— 若只读聚合表，统计页在只有图片上报的现场永远是 0。
        if (longVal(kpi.get("totalCount")) == 0 && longVal(kpi.get("defectCount")) == 0) {
            Map<String, Object> fallback = buildDefectImageSummary(start, end, factoryId, lineId, workstationId, cameraId, severity);
            if (longVal(map(fallback.get("kpi")).get("totalCount")) > 0) {
                data.putAll(fallback);
                data.put("aggregationLevel", daily ? "DAY" : "30M");
                data.put("dataSource", "DEFECT_IMAGE");
                data.put("dataSourceMessage", "当前范围聚合表无数据，已自动切换到缺陷图片事实表统计。");
            }
        }
        if (!data.containsKey("dataSource")) {
            data.put("dataSource", "AGGREGATION");
            data.put("dataSourceMessage", "当前使用质量聚合表统计。");
        }

        // ---- 工厂厂长视角指标（严重度分布 / 帕累托 / 总览 / 缺陷趋势）----
        // ⚠️ 严重度分布必须用「与主数据源同源」的查询，否则兜底路径下会被空结果覆盖。
        Object severityRows = "DEFECT_IMAGE".equals(data.get("dataSource"))
                ? data.get("severityDistribution")
                : buildSeverityDistribution(defectTable, timeColumn, daily, start, end,
                        factoryId, lineId, workstationId, cameraId, productId, shiftId, severity);
        data.put("severityDistribution", severityRows);
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
                                                        Long cameraId, String severity) {
        Map<String, Object> data = new LinkedHashMap<>();

        // 图片表的 camera_id 存的是相机编号（camera_code），需经 dim_camera 关联出维度
        String joinCam = " LEFT JOIN dim_camera c ON c.camera_code = f.camera_id ";
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE f.capture_time BETWEEN ? AND ? ");
        params.add(Timestamp.valueOf(start));
        params.add(Timestamp.valueOf(end));
        if (factoryId != null) { where.append(" AND c.factory_id = ? "); params.add(factoryId); }
        if (lineId != null) { where.append(" AND c.line_id = ? "); params.add(lineId); }
        if (workstationId != null) { where.append(" AND c.workstation_id = ? "); params.add(workstationId); }
        if (cameraId != null) { where.append(" AND c.camera_id = ? "); params.add(cameraId); }
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

        return data;
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
                longOrNull(body.get("defectTypeId")), strOrNull(body.get("severity")));
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
                                               @RequestParam(required = false) String severity) throws Exception {
        Map<String, Object> widget = findWidget(id);
        if (widget == null) {
            return ResponseEntity.notFound().build();
        }
        LocalDateTime start = parseTime(startTime, LocalDateTime.of(LocalDate.now(), LocalTime.MIN));
        LocalDateTime end = parseTime(endTime, LocalDateTime.of(LocalDate.now(), LocalTime.MAX.withNano(0)));
        Map<String, Object> data = buildSummary(start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId, defectTypeId, severity);
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
        ensureDailyAggregationTables();
        ensureAggregationJobTable();
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
        ensureAggregationScheduleTable();
        return ResultVO.success(findAggregationSchedule());
    }

    @PutMapping("/aggregation/schedule")
    public ResultVO<Map<String, Object>> updateAggregationSchedule(@RequestBody(required = false) Map<String, Object> body) {
        ensureAggregationScheduleTable();
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
        ensureAggregationJobTable();
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
        ensureAggregationJobTable();
        Map<String, Object> job = findAggregationJob(id);
        if (job.isEmpty()) return ResultVO.fail("聚合任务不存在");
        return ResultVO.success(job);
    }

    @PostMapping("/aggregation/jobs/{id}/retry")
    public ResultVO<Map<String, Object>> retryAggregationJob(@PathVariable Long id) {
        ensureDailyAggregationTables();
        ensureAggregationJobTable();
        Map<String, Object> job = findAggregationJob(id);
        if (job.isEmpty()) return ResultVO.fail("聚合任务不存在");
        LocalDate startDate = parseDate(strOrNull(job.get("startDate")), LocalDate.now().minusDays(7));
        LocalDate endDate = parseDate(strOrNull(job.get("endDate")), LocalDate.now());
        return ResultVO.success(submitDailyAggregation(startDate, endDate, longOrNull(job.get("factoryId")), longOrNull(job.get("lineId")), "RETRY", id));
    }

    @GetMapping("/aggregation/jobs/recent")
    public ResultVO<List<Map<String, Object>>> recentAggregationJobs() {
        ensureAggregationJobTable();
        return ResultVO.success(aggregationRepository.queryRecentAggregationJobs());
    }

    @Scheduled(fixedDelay = 60000)
    public void runScheduledDailyAggregationIfNeeded() {
        try {
            ensureAggregationScheduleTable();
            Map<String, Object> cfg = findAggregationSchedule();
            if (!boolVal(cfg.get("enabled"), true)) return;
            LocalTime scheduleTime = parseScheduleTime(strOrNull(cfg.get("scheduleTime")));
            LocalDate today = LocalDate.now();
            String lastRunDate = strOrNull(cfg.get("lastRunDate"));
            if (LocalTime.now().isBefore(scheduleTime) || today.toString().equals(lastRunDate)) return;
            int lookbackDays = Math.max(1, Math.min(365, intVal(cfg.get("lookbackDays"), 7)));
            LocalDate startDate = today.minusDays(lookbackDays - 1L);
            Map<String, Object> job = submitDailyAggregation(startDate, today, longOrNull(cfg.get("factoryId")), longOrNull(cfg.get("lineId")), "SCHEDULE", null);
            aggregationRepository.updateLastRun(today, longOrNull(job.get("jobId")));
        } catch (Exception ignored) {
            // 定时检查不影响主服务；错误会在具体聚合任务中记录。
        }
    }

    private Map<String, Object> submitDailyAggregation(LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId, String triggerSource, Long retryFromJobId) {
        // ★ 必须先确保两张日聚合表存在。
        //   之前只有 runDailyAggregation / retryAggregationJob 两条入口做了 ensure，
        //   而 @Scheduled 定时任务走的 submitDailyAggregation 直接就 DELETE，
        //   线上实测报 "Table 'wms-new.agg_quality_day' doesn't exist" → 任务 FAILED。
        ensureDailyAggregationTables();
        ensureAggregationJobTable();
        Long jobId = createAggregationJob(startDate, endDate, factoryId, lineId, triggerSource, retryFromJobId);
        Map<String, Object> job = findAggregationJob(jobId);
        aggregationExecutor.submit(() -> {
            try {
                int qualityRows = rebuildQualityDaily(startDate, endDate, factoryId, lineId);
                int defectRows = rebuildDefectDaily(startDate, endDate, factoryId, lineId);
                updateAggregationJob(jobId, "SUCCESS", qualityRows, defectRows, "日聚合完成", null);
            } catch (Exception e) {
                String detail = stackTraceText(e);
                updateAggregationJob(jobId, "FAILED", 0, 0, clip(e.getMessage(), 500), detail);
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
    private final java.util.concurrent.atomic.AtomicBoolean scheduleTableEnsured =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean dailyAggTablesEnsured =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean aggregationJobTableEnsured =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 判断表是否真实存在（查 INFORMATION_SCHEMA，不执行任何 DDL）。
     */
    private boolean tableExists(String tableName) {
        return aggregationRepository.tableExists(tableName);
    }

    private void ensureAggregationScheduleTable() {
        if (tableExists("analytics_aggregation_schedule")) {
            scheduleTableEnsured.set(true);
            return;   // ★ 表在，不执行 DDL
        }
        try {
        aggregationRepository.ensureAggregationScheduleTable();
        scheduleTableEnsured.set(true);
        } catch (Exception e) {
            // 建表失败要让标志位复位，否则应用再也不会尝试建表
            scheduleTableEnsured.set(false);
            throw e;
        }
    }

    private Map<String, Object> findAggregationSchedule() {
        ensureAggregationScheduleTable();
        Map<String, Object> row = aggregationRepository.findAggregationSchedule();
        row.put("enabled", boolVal(row.get("enabled"), true));
        return row;
    }

    private void ensureDailyAggregationTables() {
        if (tableExists("agg_quality_day") && tableExists("agg_defect_type_day")) {
            dailyAggTablesEnsured.set(true);
            return;   // ★ 表在，不执行 DDL
        }
        try {
        aggregationRepository.ensureDailyAggregationTables();
        dailyAggTablesEnsured.set(true);
        } catch (Exception e) {
            dailyAggTablesEnsured.set(false);
            throw e;
        }
    }


    private void ensureAggregationJobTable() {
        if (tableExists("analytics_aggregation_job")) {
            aggregationJobTableEnsured.set(true);
            return;   // ★ 表在，不执行 DDL
        }
        try {
        aggregationRepository.ensureAggregationJobTable();
        aggregationJobTableEnsured.set(true);
        } catch (Exception e) {
            aggregationJobTableEnsured.set(false);
            throw e;
        }
    }

    private Long createAggregationJob(LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId, String triggerSource, Long retryFromJobId) {
        return aggregationRepository.createAggregationJob(startDate, endDate, factoryId, lineId, triggerSource, retryFromJobId);
    }

    private void updateAggregationJob(Long jobId, String status, int qualityRows, int defectRows, String message, String errorDetail) {
        try {
            ensureAggregationJobTable();
            aggregationRepository.updateAggregationJob(jobId, status, qualityRows, defectRows, message, errorDetail);
        } catch (Exception ignored) {
            // 聚合线程中不再向外抛出，避免影响服务主流程。
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
