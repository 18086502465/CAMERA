package com.wms.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.repository.analytics.LineAnalyticsRepository;
import com.wms.vo.ResultVO;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class LineAnalyticsController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LineAnalyticsRepository lineAnalyticsRepository;

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
        Map<String, Object> kpi = one(
                "SELECT COALESCE(SUM(total_count),0) AS totalCount, COALESCE(SUM(ok_count),0) AS okCount, COALESCE(SUM(ng_count),0) AS ngCount, " +
                        "COALESCE(SUM(defect_count),0) AS defectCount FROM " + qualityTable + " " + qualityWhere,
                qualityParams);
        long total = longVal(kpi.get("totalCount"));
        long ok = longVal(kpi.get("okCount"));
        long ng = longVal(kpi.get("ngCount"));
        kpi.put("yieldRate", total > 0 ? Math.round(ok * 10000.0 / total) / 100.0 : 0.0);
        kpi.put("ngRate", total > 0 ? Math.round(ng * 10000.0 / total) / 100.0 : 0.0);
        data.put("kpi", kpi);

        data.put("trend", jdbcTemplate.queryForList(
                "SELECT " + timeColumn + " AS timeBucket, SUM(total_count) AS totalCount, SUM(ok_count) AS okCount, SUM(ng_count) AS ngCount, " +
                        "IF(SUM(total_count)>0, ROUND(SUM(ok_count)*100/SUM(total_count),2),0) AS yieldRate " +
                        "FROM " + qualityTable + " " + qualityWhere + " GROUP BY " + timeColumn + " ORDER BY " + timeColumn,
                qualityParams.toArray()));

        List<Object> defectParams = new ArrayList<>();
        String defectWhere = buildDefectWhere(timeColumn, daily, start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId, defectTypeId, severity, defectParams);
        data.put("defectRanking", jdbcTemplate.queryForList(
                "SELECT defect_type_id AS defectTypeId, defect_code AS defectCode, defect_name AS defectName, severity, SUM(defect_count) AS defectCount " +
                        "FROM " + defectTable + " " + defectWhere + " GROUP BY defect_type_id, defect_code, defect_name, severity ORDER BY defectCount DESC LIMIT 20",
                defectParams.toArray()));
        List<Object> stationParams = new ArrayList<>();
        String stationWhere = buildQualityWhereAlias("q", timeColumn, daily, start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId, stationParams);
        data.put("stationRanking", jdbcTemplate.queryForList(
                "SELECT q.workstation_id AS workstationId, w.station_name AS workstationName, SUM(q.total_count) AS totalCount, SUM(q.ok_count) AS okCount, SUM(q.ng_count) AS ngCount, " +
                        "IF(SUM(q.total_count)>0, ROUND(SUM(q.ok_count)*100/SUM(q.total_count),2),0) AS yieldRate " +
                        "FROM " + qualityTable + " q LEFT JOIN dim_workstation w ON q.workstation_id = w.workstation_id " +
                        stationWhere +
                        " GROUP BY q.workstation_id, w.station_name ORDER BY ngCount DESC, totalCount DESC LIMIT 20",
                stationParams.toArray()));
        List<Object> cameraParams = new ArrayList<>();
        String cameraWhere = buildQualityWhereAlias("q", timeColumn, daily, start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId, cameraParams);
        data.put("cameraRanking", jdbcTemplate.queryForList(
                "SELECT q.camera_id AS cameraId, c.camera_name AS cameraName, c.camera_code AS cameraCode, SUM(q.total_count) AS totalCount, SUM(q.ok_count) AS okCount, SUM(q.ng_count) AS ngCount, " +
                        "IF(SUM(q.total_count)>0, ROUND(SUM(q.ok_count)*100/SUM(q.total_count),2),0) AS yieldRate " +
                        "FROM " + qualityTable + " q LEFT JOIN dim_camera c ON q.camera_id = c.camera_id " +
                        cameraWhere +
                        " GROUP BY q.camera_id, c.camera_name, c.camera_code ORDER BY ngCount DESC, totalCount DESC LIMIT 20",
                cameraParams.toArray()));
        data.put("defectPivot", buildDefectPivot(defectTable, timeColumn, daily, start, end, factoryId, lineId, workstationId, cameraId, productId, shiftId));
        data.put("mappingExceptions", safeList(
                "SELECT exception_id AS exceptionId, camera_code AS cameraCode, product_name AS productName, source_code AS sourceCode, exception_type AS exceptionType, exception_message AS exceptionMessage, create_time AS createTime " +
                        "FROM fact_inspection_mapping_exception WHERE status = 'OPEN' ORDER BY create_time DESC LIMIT 10"));
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
        jdbcTemplate.update(
                "INSERT INTO cfg_analytics_dashboard (dashboard_name, factory_id, owner_user_id, scope_type, is_default, layout_json, is_enabled) " +
                        "VALUES (?,?,?,?,?,CAST(? AS JSON),1)",
                name, factoryId, ownerUserId, scopeType, isDefault, layoutJson);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
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
        jdbcTemplate.update(
                "INSERT INTO cfg_analytics_dashboard (dashboard_name, factory_id, owner_user_id, scope_type, is_default, layout_json, is_enabled) " +
                        "SELECT ?, ?, ?, ?, ?, layout_json, 1 FROM cfg_analytics_dashboard WHERE dashboard_id=? AND is_enabled=1",
                name, factoryId, ownerUserId, scopeType, isDefault, id);
        Long newId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update(
                "INSERT INTO cfg_analytics_widget (dashboard_id, widget_name, widget_type, metric_json, dimension_json, filter_json, option_json, layout_json, follow_global_filter, sort_order, is_enabled) " +
                        "SELECT ?, widget_name, widget_type, metric_json, dimension_json, filter_json, option_json, layout_json, follow_global_filter, sort_order, is_enabled " +
                        "FROM cfg_analytics_widget WHERE dashboard_id=? AND is_enabled=1 ORDER BY sort_order, widget_id",
                newId, id);
        return dashboardDetail(newId);
    }

    @PutMapping("/dashboards/{id}")
    public ResultVO<Map<String, Object>> updateDashboard(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String name = required(body, "dashboardName", "看板名称不能为空");
        String scopeType = firstNonBlank(strOrNull(body.get("scopeType")), "FACTORY");
        String layoutJson = jsonValue(body.get("layoutJson"), "{\"cols\":12,\"rowHeight\":120}");
        jdbcTemplate.update(
                "UPDATE cfg_analytics_dashboard SET dashboard_name=?, factory_id=?, owner_user_id=?, scope_type=?, is_default=?, layout_json=CAST(? AS JSON) WHERE dashboard_id=?",
                name, longOrNull(body.get("factoryId")), longOrNull(body.get("ownerUserId")), scopeType,
                intVal(body.get("isDefault"), 0), layoutJson, id);
        return dashboardDetail(id);
    }

    @DeleteMapping("/dashboards/{id}")
    public ResultVO<String> deleteDashboard(@PathVariable Long id) {
        jdbcTemplate.update("UPDATE cfg_analytics_dashboard SET is_enabled = 0 WHERE dashboard_id = ?", id);
        jdbcTemplate.update("UPDATE cfg_analytics_widget SET is_enabled = 0 WHERE dashboard_id = ?", id);
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
        jdbcTemplate.update(
                "INSERT INTO cfg_analytics_widget (dashboard_id, widget_name, widget_type, metric_json, dimension_json, filter_json, option_json, layout_json, follow_global_filter, sort_order, is_enabled) " +
                        "VALUES (?,?,?,CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),?,?,1)",
                dashboardId, widgetName, widgetType,
                jsonValue(body.get("metricJson"), "{}"),
                jsonValue(body.get("dimensionJson"), "[]"),
                jsonValue(body.get("filterJson"), "{}"),
                jsonValue(body.get("optionJson"), "{}"),
                jsonValue(body.get("layoutJson"), "{\"w\":6,\"h\":2}"),
                intVal(body.get("followGlobalFilter"), 1),
                intVal(body.get("sortOrder"), 0));
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return ResultVO.success(findWidget(id));
    }

    @PutMapping("/widgets/{id}")
    public ResultVO<Map<String, Object>> updateWidget(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String widgetName = required(body, "widgetName", "图表名称不能为空");
        String widgetType = firstNonBlank(strOrNull(body.get("widgetType")), "KPI").toUpperCase(Locale.ROOT);
        jdbcTemplate.update(
                "UPDATE cfg_analytics_widget SET widget_name=?, widget_type=?, metric_json=CAST(? AS JSON), dimension_json=CAST(? AS JSON), " +
                        "filter_json=CAST(? AS JSON), option_json=CAST(? AS JSON), layout_json=CAST(? AS JSON), follow_global_filter=?, sort_order=?, is_enabled=? WHERE widget_id=?",
                widgetName, widgetType,
                jsonValue(body.get("metricJson"), "{}"),
                jsonValue(body.get("dimensionJson"), "[]"),
                jsonValue(body.get("filterJson"), "{}"),
                jsonValue(body.get("optionJson"), "{}"),
                jsonValue(body.get("layoutJson"), "{\"w\":6,\"h\":2}"),
                intVal(body.get("followGlobalFilter"), 1),
                intVal(body.get("sortOrder"), 0),
                intVal(body.get("isEnabled"), 1),
                id);
        return ResultVO.success(findWidget(id));
    }

    @DeleteMapping("/widgets/{id}")
    public ResultVO<String> deleteWidget(@PathVariable Long id) {
        jdbcTemplate.update("UPDATE cfg_analytics_widget SET is_enabled = 0 WHERE widget_id = ?", id);
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
                jdbcTemplate.update("UPDATE cfg_analytics_widget SET sort_order=?, layout_json=CAST(? AS JSON) WHERE widget_id=?",
                        intVal(item.get("sortOrder"), 0),
                        jsonValue(item.get("layoutJson"), "{\"w\":6,\"h\":2}"),
                        widgetId);
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
        jdbcTemplate.update(
                "UPDATE analytics_aggregation_schedule SET enabled=?, schedule_time=?, lookback_days=?, factory_id=?, line_id=?, update_time=NOW() WHERE config_id=1",
                enabled, time.toString(), lookbackDays, factoryId, lineId);
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
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM analytics_aggregation_job" + where, params.toArray(), Long.class);
        List<Object> listParams = new ArrayList<>(params);
        listParams.add((p - 1) * size);
        listParams.add(size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "SELECT job_id AS jobId, job_type AS jobType, status, start_date AS startDate, end_date AS endDate, factory_id AS factoryId, line_id AS lineId, " +
                        "quality_rows AS qualityRows, defect_rows AS defectRows, message, error_detail AS errorDetail, trigger_source AS triggerSource, retry_from_job_id AS retryFromJobId, " +
                        "create_time AS createTime, start_time AS startTime, finish_time AS finishTime " +
                        "FROM analytics_aggregation_job " + where + " ORDER BY job_id DESC LIMIT ?, ?",
                listParams.toArray());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", records);
        data.put("total", total == null ? 0 : total);
        data.put("page", p);
        data.put("pageSize", size);
        return ResultVO.success(data);
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
        return ResultVO.success(jdbcTemplate.queryForList(
                "SELECT job_id AS jobId, job_type AS jobType, status, start_date AS startDate, end_date AS endDate, factory_id AS factoryId, line_id AS lineId, " +
                        "quality_rows AS qualityRows, defect_rows AS defectRows, message, error_detail AS errorDetail, trigger_source AS triggerSource, retry_from_job_id AS retryFromJobId, create_time AS createTime, start_time AS startTime, finish_time AS finishTime " +
                        "FROM analytics_aggregation_job ORDER BY job_id DESC LIMIT 20"));
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
            jdbcTemplate.update("UPDATE analytics_aggregation_schedule SET last_run_date=?, last_job_id=?, update_time=NOW() WHERE config_id=1",
                    java.sql.Date.valueOf(today), longOrNull(job.get("jobId")));
        } catch (Exception ignored) {
            // 定时检查不影响主服务；错误会在具体聚合任务中记录。
        }
    }

    private Map<String, Object> submitDailyAggregation(LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId, String triggerSource, Long retryFromJobId) {
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
     * <p>修复：DDL <b>只在首次调用时执行一次</b>（进程内标志位），
     * 之后直接跳过。若表被人为删掉，重启应用即可重建。
     */
    private final java.util.concurrent.atomic.AtomicBoolean scheduleTableEnsured =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean dailyAggTablesEnsured =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean aggregationJobTableEnsured =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void ensureAggregationScheduleTable() {
        if (!scheduleTableEnsured.compareAndSet(false, true)) {
            return;   // ★ 已建过，直接跳过，不再重复执行 DDL
        }
        try {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS analytics_aggregation_schedule (" +
                "config_id BIGINT NOT NULL PRIMARY KEY," +
                "enabled TINYINT NOT NULL DEFAULT 1," +
                "schedule_time TIME NOT NULL DEFAULT '02:00:00'," +
                "lookback_days INT NOT NULL DEFAULT 7," +
                "factory_id BIGINT NULL," +
                "line_id BIGINT NULL," +
                "last_run_date DATE NULL," +
                "last_job_id BIGINT NULL," +
                "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产线统计日聚合定时配置表'");
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM analytics_aggregation_schedule WHERE config_id=1", Integer.class);
        if (count == null || count == 0) {
            jdbcTemplate.update("INSERT INTO analytics_aggregation_schedule(config_id, enabled, schedule_time, lookback_days) VALUES (1,1,'02:00:00',7)");
        }
        } catch (Exception e) {
            // 建表失败要让标志位复位，否则应用再也不会尝试建表
            scheduleTableEnsured.set(false);
            throw e;
        }
    }

    private Map<String, Object> findAggregationSchedule() {
        ensureAggregationScheduleTable();
        Map<String, Object> row = one("SELECT config_id AS configId, enabled, TIME_FORMAT(schedule_time,'%H:%i') AS scheduleTime, lookback_days AS lookbackDays, " +
                "factory_id AS factoryId, line_id AS lineId, last_run_date AS lastRunDate, last_job_id AS lastJobId, create_time AS createTime, update_time AS updateTime " +
                "FROM analytics_aggregation_schedule WHERE config_id=1", new ArrayList<>());
        row.put("enabled", boolVal(row.get("enabled"), true));
        return row;
    }

    private void ensureDailyAggregationTables() {
        if (!dailyAggTablesEnsured.compareAndSet(false, true)) {
            return;   // ★ 已建过，直接跳过
        }
        try {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS agg_quality_day (" +
                "agg_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                "date_bucket DATE NOT NULL," +
                "factory_id INT NOT NULL," +
                "line_id INT NOT NULL," +
                "workstation_id INT NOT NULL," +
                "camera_id BIGINT NOT NULL," +
                "product_id BIGINT NOT NULL," +
                "shift_id BIGINT NOT NULL DEFAULT 0," +
                "total_count BIGINT NOT NULL DEFAULT 0," +
                "ok_count BIGINT NOT NULL DEFAULT 0," +
                "ng_count BIGINT NOT NULL DEFAULT 0," +
                "defect_count BIGINT NOT NULL DEFAULT 0," +
                "yield_rate DECIMAL(10,6) NULL," +
                "ng_rate DECIMAL(10,6) NULL," +
                "update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "UNIQUE KEY uk_quality_day_scope(date_bucket,factory_id,line_id,workstation_id,camera_id,product_id,shift_id)," +
                "KEY idx_quality_day_factory(date_bucket,factory_id)," +
                "KEY idx_quality_day_line(date_bucket,line_id)," +
                "KEY idx_quality_day_product(date_bucket,product_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量日聚合表'");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS agg_defect_type_day (" +
                "agg_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                "date_bucket DATE NOT NULL," +
                "factory_id INT NOT NULL," +
                "line_id INT NOT NULL," +
                "workstation_id INT NOT NULL," +
                "camera_id BIGINT NOT NULL," +
                "product_id BIGINT NOT NULL," +
                "shift_id BIGINT NOT NULL DEFAULT 0," +
                "defect_type_id BIGINT NOT NULL," +
                "defect_code VARCHAR(50) NOT NULL," +
                "defect_name VARCHAR(100) NOT NULL," +
                "position_id BIGINT NULL," +
                "position_code VARCHAR(50) NULL," +
                "severity VARCHAR(20) NULL," +
                "defect_count BIGINT NOT NULL DEFAULT 0," +
                "update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "UNIQUE KEY uk_defect_day_scope(date_bucket,factory_id,line_id,workstation_id,camera_id,product_id,shift_id,defect_type_id)," +
                "KEY idx_defect_day_type(date_bucket,defect_type_id)," +
                "KEY idx_defect_day_line(date_bucket,line_id)," +
                "KEY idx_defect_day_product(date_bucket,product_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缺陷类型日聚合表'");
        } catch (Exception e) {
            dailyAggTablesEnsured.set(false);
            throw e;
        }
    }


    private void ensureAggregationJobTable() {
        if (!aggregationJobTableEnsured.compareAndSet(false, true)) {
            return;   // ★ 已建过，直接跳过
        }
        try {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS analytics_aggregation_job (" +
                "job_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                "job_type VARCHAR(50) NOT NULL," +
                "status VARCHAR(20) NOT NULL," +
                "start_date DATE NULL," +
                "end_date DATE NULL," +
                "factory_id BIGINT NULL," +
                "line_id BIGINT NULL," +
                "quality_rows INT NOT NULL DEFAULT 0," +
                "defect_rows INT NOT NULL DEFAULT 0," +
                "message VARCHAR(500) NULL," +
                "error_detail TEXT NULL," +
                "trigger_source VARCHAR(30) NOT NULL DEFAULT 'MANUAL'," +
                "retry_from_job_id BIGINT NULL," +
                "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "start_time DATETIME NULL," +
                "finish_time DATETIME NULL," +
                "KEY idx_job_create_time(create_time)," +
                "KEY idx_job_status(status)," +
                "KEY idx_job_trigger_source(trigger_source)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产线统计聚合任务表'");
        addColumnQuiet("analytics_aggregation_job", "error_detail", "ALTER TABLE analytics_aggregation_job ADD COLUMN error_detail TEXT NULL AFTER message");
        addColumnQuiet("analytics_aggregation_job", "trigger_source", "ALTER TABLE analytics_aggregation_job ADD COLUMN trigger_source VARCHAR(30) NOT NULL DEFAULT 'MANUAL' AFTER error_detail");
        addColumnQuiet("analytics_aggregation_job", "retry_from_job_id", "ALTER TABLE analytics_aggregation_job ADD COLUMN retry_from_job_id BIGINT NULL AFTER trigger_source");
        } catch (Exception e) {
            aggregationJobTableEnsured.set(false);
            throw e;
        }
    }

    private Long createAggregationJob(LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId, String triggerSource, Long retryFromJobId) {
        jdbcTemplate.update(
                "INSERT INTO analytics_aggregation_job(job_type,status,start_date,end_date,factory_id,line_id,message,trigger_source,retry_from_job_id,start_time) " +
                        "VALUES ('DAILY_AGGREGATION','RUNNING',?,?,?,?,?,?,?,NOW())",
                java.sql.Date.valueOf(startDate), java.sql.Date.valueOf(endDate), factoryId, lineId, "日聚合任务已提交", triggerSource, retryFromJobId);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void updateAggregationJob(Long jobId, String status, int qualityRows, int defectRows, String message, String errorDetail) {
        try {
            ensureAggregationJobTable();
            jdbcTemplate.update("UPDATE analytics_aggregation_job SET status=?, quality_rows=?, defect_rows=?, message=?, error_detail=?, finish_time=NOW() WHERE job_id=?",
                    status, qualityRows, defectRows, clip(message, 500), clip(errorDetail, 4000), jobId);
        } catch (Exception ignored) {
            // 聚合线程中不再向外抛出，避免影响服务主流程。
        }
    }

    private Map<String, Object> findAggregationJob(Long jobId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT job_id AS jobId, job_type AS jobType, status, start_date AS startDate, end_date AS endDate, factory_id AS factoryId, line_id AS lineId, " +
                        "quality_rows AS qualityRows, defect_rows AS defectRows, message, error_detail AS errorDetail, trigger_source AS triggerSource, retry_from_job_id AS retryFromJobId, create_time AS createTime, start_time AS startTime, finish_time AS finishTime " +
                        "FROM analytics_aggregation_job WHERE job_id=?", jobId);
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }
    private int rebuildQualityDaily(LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId) {
        List<Object> deleteParams = new ArrayList<>();
        String deleteWhere = buildDailyDeleteWhere("date_bucket", startDate, endDate, factoryId, lineId, deleteParams);
        jdbcTemplate.update("DELETE FROM agg_quality_day " + deleteWhere, deleteParams.toArray());

        List<Object> insertParams = new ArrayList<>();
        String sourceWhere = buildDailySourceWhere("time_bucket", startDate, endDate, factoryId, lineId, insertParams);
        return jdbcTemplate.update(
                "INSERT INTO agg_quality_day(date_bucket,factory_id,line_id,workstation_id,camera_id,product_id,shift_id,total_count,ok_count,ng_count,defect_count,yield_rate,ng_rate) " +
                        "SELECT DATE(time_bucket), factory_id, line_id, workstation_id, camera_id, product_id, shift_id, " +
                        "SUM(total_count), SUM(ok_count), SUM(ng_count), SUM(defect_count), " +
                        "IF(SUM(total_count)>0, SUM(ok_count)/SUM(total_count), 0), IF(SUM(total_count)>0, SUM(ng_count)/SUM(total_count), 0) " +
                        "FROM agg_quality_30m " + sourceWhere +
                        " GROUP BY DATE(time_bucket), factory_id, line_id, workstation_id, camera_id, product_id, shift_id",
                insertParams.toArray());
    }

    private int rebuildDefectDaily(LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId) {
        List<Object> deleteParams = new ArrayList<>();
        String deleteWhere = buildDailyDeleteWhere("date_bucket", startDate, endDate, factoryId, lineId, deleteParams);
        jdbcTemplate.update("DELETE FROM agg_defect_type_day " + deleteWhere, deleteParams.toArray());

        List<Object> insertParams = new ArrayList<>();
        String sourceWhere = buildDailySourceWhere("time_bucket", startDate, endDate, factoryId, lineId, insertParams);
        return jdbcTemplate.update(
                "INSERT INTO agg_defect_type_day(date_bucket,factory_id,line_id,workstation_id,camera_id,product_id,shift_id,defect_type_id,defect_code,defect_name,position_id,position_code,severity,defect_count) " +
                        "SELECT DATE(time_bucket), factory_id, line_id, workstation_id, camera_id, product_id, shift_id, defect_type_id, defect_code, defect_name, position_id, position_code, severity, SUM(defect_count) " +
                        "FROM agg_defect_type_30m " + sourceWhere +
                        " GROUP BY DATE(time_bucket), factory_id, line_id, workstation_id, camera_id, product_id, shift_id, defect_type_id, defect_code, defect_name, position_id, position_code, severity",
                insertParams.toArray());
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
        return jdbcTemplate.queryForList(
                "SELECT product_id AS productId, defect_type_id AS defectTypeId, defect_code AS defectCode, defect_name AS defectName, SUM(defect_count) AS defectCount " +
                        "FROM " + defectTable + " " + where + " GROUP BY product_id, defect_type_id, defect_code, defect_name ORDER BY product_id, defectCount DESC",
                params.toArray());
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
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, tableName, columnName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(ddl);
            }
        } catch (Exception ignored) {
            // 兼容老库字段补齐，失败时由后续 SQL 暴露真实错误。
        }
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
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> one(String sql, List<Object> params) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
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

    private Long createDefaultDashboard(Long factoryId) {
        jdbcTemplate.update(
                "INSERT INTO cfg_analytics_dashboard (dashboard_name, factory_id, scope_type, is_default, layout_json, is_enabled) " +
                        "VALUES (?,?,?,1,CAST(? AS JSON),1)",
                "默认产线统计看板", factoryId, "FACTORY", "{\"cols\":12,\"rowHeight\":120}");
        Long dashboardId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        insertDefaultWidget(dashboardId, "核心质量指标", "KPI", "{\"metric\":\"quality\"}", "[\"time\"]", "{\"x\":0,\"y\":0,\"w\":12,\"h\":1}", 1);
        insertDefaultWidget(dashboardId, "30分钟质量趋势", "LINE", "{\"metric\":\"trend\"}", "[\"time\"]", "{\"x\":0,\"y\":1,\"w\":12,\"h\":3}", 2);
        insertDefaultWidget(dashboardId, "缺陷类型排行", "RANK", "{\"metric\":\"defectRanking\"}", "[\"defectType\"]", "{\"x\":0,\"y\":4,\"w\":6,\"h\":3}", 3);
        insertDefaultWidget(dashboardId, "异常工位排行", "RANK", "{\"metric\":\"stationRanking\"}", "[\"workstation\"]", "{\"x\":6,\"y\":4,\"w\":6,\"h\":3}", 4);
        insertDefaultWidget(dashboardId, "产品缺陷透视表", "PIVOT", "{\"metric\":\"defectPivot\"}", "[\"product\",\"defectType\"]", "{\"x\":0,\"y\":7,\"w\":12,\"h\":3}", 5);
        return dashboardId;
    }

    private void insertDefaultWidget(Long dashboardId, String name, String type, String metricJson,
                                     String dimensionJson, String layoutJson, int sortOrder) {
        jdbcTemplate.update(
                "INSERT INTO cfg_analytics_widget (dashboard_id, widget_name, widget_type, metric_json, dimension_json, filter_json, option_json, layout_json, follow_global_filter, sort_order, is_enabled) " +
                        "VALUES (?,?,?,CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),1,?,1)",
                dashboardId, name, type, metricJson, dimensionJson, "{}", "{}", layoutJson, sortOrder);
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
