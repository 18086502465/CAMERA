package com.wms.analytics.api;

import com.wms.analytics.infra.repository.DailyReportRepository;
import com.wms.analytics.infra.task.DailyReportGenerationTask;
import com.wms.shared.common.ResultVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产线统计日报（2026-11-17 新增，PRD 见 docs/archive/2026-11-17-产线统计日报PRD.md）。
 *
 * <p>端点全部挂 /api/v1/analytics/daily-reports 下，沿用管理端默认鉴权
 * （未在 SecurityConfig 白名单 → 必须登录）；{@code regenerate} 额外要求 ADMIN。
 *
 * <p>三个关键口径（与 PRD 一致，改口径先改文档再改这里）：
 * <ul>
 *   <li><b>dataSource=SNAPSHOT</b>：历史日读快照表；<b>REALTIME</b>：当天实时计算
 *       （当天不入库，每分钟都在变）；</li>
 *   <li><b>无数据 ≠ 0</b>：detectCount=0 的行 {@code defectRateKilo=null}，前端显示
 *       「无数据」；</li>
 *   <li><b>样本下限</b>：detectCount &lt; {@link DailyReportRepository#MIN_RANK_SAMPLE}
 *       时 {@code sampleInsufficient=true}，环比与「最差相机」仅供参考。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/analytics/daily-reports")
public class DailyReportController {

    private static final Logger log = LoggerFactory.getLogger(DailyReportController.class);

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 与 PRD D3 一致：本期不做目标值，列表只给环比不给达成率 */
    private static final Map<String, String> CALIBER = buildCaliber();

    private final DailyReportRepository dailyReportRepository;
    private final DailyReportGenerationTask generationTask;

    public DailyReportController(DailyReportRepository dailyReportRepository,
                                 DailyReportGenerationTask generationTask) {
        this.dailyReportRepository = dailyReportRepository;
        this.generationTask = generationTask;
    }

    private static Map<String, String> buildCaliber() {
        Map<String, String> caliber = new LinkedHashMap<>();
        caliber.put("rateFormula", "缺陷率(‰) = NG计数日增 / 检测总数日增 × 1000（计数口径，主口径）");
        caliber.put("rateDenominator", "检测总数日增（心跳累计计数差分）");
        caliber.put("counterNote", "图片通道只上报缺陷图，图片口径恒为 1000‰ 无区分度（2026-11-17 实测）；"
                + "计数口径自第二份快照起可用，计数器被重置的当天显示「待积累」");
        caliber.put("cameraType", "REAL（TEST 与未登记相机不进日报）");
        caliber.put("rankMinSample", String.valueOf(DailyReportRepository.MIN_RANK_SAMPLE));
        return caliber;
    }

    // ==================== 列表（每天一行，厂级汇总） ====================

    /**
     * 日报列表。默认近 30 天（含今天）；今天没有快照就实时算一行补在最后
     * （响应行带 {@code dataSource=REALTIME}，其余为 SNAPSHOT）。
     */
    @GetMapping
    public ResultVO<Map<String, Object>> list(@RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(required = false) Integer lineId) {
        LocalDate end = parseDate(endDate, LocalDate.now());
        LocalDate start = parseDate(startDate, end.minusDays(29));
        if (start.isAfter(end)) {
            return ResultVO.fail("开始日期不能晚于结束日期");
        }
        LocalDate today = LocalDate.now();
        // 快照只可能有历史日；区间与今天的交集部分实时算
        LocalDate snapshotEnd = end.isBefore(today) ? end : today.minusDays(1);

        List<Map<String, Object>> rows = new ArrayList<>();
        if (!snapshotEnd.isBefore(start)) {
            // 快照行在这里统一 decorate（环比/样本标记/dataSource）；实时行在下面单独 decorate，
            // 不能最后统一 forEach —— 会把实时行的 REALTIME 覆盖成 SNAPSHOT
            for (Map<String, Object> row : dailyReportRepository.listRange(start, snapshotEnd, lineId)) {
                decorate(row, toLocalDate(row.get("reportDate")), "SNAPSHOT");
                rows.add(row);
            }
        }
        if (!end.isBefore(today)) {
            // 今天（或区间下界落在今天）实时算；只取需要的那天
            LocalDate liveFrom = start.isAfter(today) ? start : today;
            for (LocalDate d = liveFrom; !d.isAfter(end); d = d.plusDays(1)) {
                for (Map<String, Object> row : dailyReportRepository.computeDay(d, !d.isAfter(today))) {
                    if (lineId == null ? Integer.valueOf(0).equals(row.get("lineId"))
                            : lineId.equals(((Number) row.get("lineId")).intValue())) {
                        decorate(row, d, "REALTIME");
                        rows.add(row);
                    }
                }
            }
        }
        rows.sort((a, b) -> toLocalDate(b.get("reportDate")).compareTo(toLocalDate(a.get("reportDate"))));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", rows);
        data.put("caliber", CALIBER);
        return ResultVO.success(data);
    }

    // ==================== 详情（单日：厂级 + 产线分解） ====================

    /** 单日详情：历史日读快照；当天实时算。含 TOP3 缺陷类型（解析自 JSON）与最差相机。 */
    @GetMapping("/{date}")
    public ResultVO<Map<String, Object>> detail(@PathVariable String date) {
        LocalDate day = parseDate(date, null);
        if (day == null) {
            return ResultVO.fail("日期格式应为 yyyy-MM-dd");
        }
        if (day.isAfter(LocalDate.now())) {
            return ResultVO.fail("不能查询未来日期");
        }
        boolean realtime = day.equals(LocalDate.now());
        List<Map<String, Object>> rows;
        String dataSource;
        if (realtime) {
            rows = dailyReportRepository.computeDay(day, true);
            dataSource = "REALTIME";
        } else {
            rows = dailyReportRepository.listRange(day, day, null);
            dataSource = "SNAPSHOT";
        }
        if (rows.isEmpty()) {
            // 无快照（如服务刚上线、历史日没生成过）→ 如实返回，不伪造 0
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reportDate", day.toString());
            data.put("dataSource", "SNAPSHOT");
            data.put("missing", true);
            data.put("missingMessage", day + " 尚无日报快照（服务上线前或生成失败），可用「补算」生成");
            data.put("caliber", CALIBER);
            return ResultVO.success(data);
        }

        Map<String, Object> factory = null;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            // 与列表同款增强：环比 + 计数口径日增/真缺陷率（快照行与实时行统一走 decorate）
            decorate(row, day, dataSource);
            int lineId = ((Number) row.get("lineId")).intValue();
            Map<String, Object> enriched = new LinkedHashMap<>(row);
            enriched.put("topDefectTypeList", parseTopTypes(row.get("topDefectTypes")));
            enriched.put("sampleInsufficient",
                    ((Number) enriched.getOrDefault("detectCount", 0)).intValue()
                            < DailyReportRepository.MIN_RANK_SAMPLE);
            if (lineId == 0) {
                factory = enriched;
            } else {
                lines.add(enriched);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportDate", day.toString());
        data.put("dataSource", dataSource);
        data.put("factory", factory);
        data.put("lines", lines);
        data.put("caliber", CALIBER);
        return ResultVO.success(data);
    }

    // ==================== 月历（产线 → 当月每天一格） ====================

    /**
     * 月历数据：某产线（或全厂）当月每天一格。
     *
     * <p>2026-11-17 交互重构新增。为什么单独一个端点而不是让前端拿 list 自己拼：
     * <ul>
     *   <li>计数口径缺陷率需要**前一天快照做差分**，按月一次取全月（多取一天做基线）
     *       在内存里算完，避免前端对 31 天各发一次详情请求；</li>
     *   <li>月历要能显示「未来日」和「无数据日」两种空态，list 只有有快照的日期，
     *       前端补不出去（不知道是没生成还是没数据）。</li>
     * </ul>
     *
     * @param month  yyyy-MM，缺省当月
     * @param lineId 产线；不传或 0 = 全厂
     */
    @GetMapping("/calendar")
    public ResultVO<Map<String, Object>> calendar(@RequestParam(required = false) String month,
                                                 @RequestParam(required = false) Integer lineId) {
        LocalDate monthStart = parseMonth(month);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        LocalDate today = LocalDate.now();
        int scope = (lineId == null || lineId == 0) ? 0 : lineId;

        // ① 取快照：多取前一天当计数差分基线
        Map<LocalDate, Map<String, Object>> byDate = new java.util.TreeMap<>();
        LocalDate snapEnd = monthEnd.isBefore(today) ? monthEnd : today.minusDays(1);
        if (!snapEnd.isBefore(monthStart)) {
            List<Map<String, Object>> snap = dailyReportRepository.listRange(
                    monthStart.minusDays(1), snapEnd, scope);
            for (Map<String, Object> row : snap) {
                LocalDate d = toLocalDate(row.get("reportDate"));
                if (d != null) byDate.put(d, row);
            }
        }

        // ② 计数口径：相邻（且恰好差 1 天）快照的累计值差分。缺日 → 差分不可信，留空
        Map<LocalDate, long[]> cumByDate = new java.util.HashMap<>();
        byDate.forEach((d, row) -> {
            Long insp = longOrNull(row.get("inspTotalCum"));
            Long ng = longOrNull(row.get("ngTotalCum"));
            if (insp != null && ng != null) cumByDate.put(d, new long[]{insp, ng});
        });

        // ③ 今天实时补一行
        Map<String, Object> todayRow = null;
        if (!today.isBefore(monthStart) && !today.isAfter(monthEnd)) {
            for (Map<String, Object> row : dailyReportRepository.computeDay(today, true)) {
                if (scope == ((Number) row.get("lineId")).intValue()) {
                    row.put("reportDate", today.toString());
                    todayRow = row;
                    break;
                }
            }
        }

        // ④ 组格：全月每一天都出一格（未来日 / 无数据日各有状态，前端不用猜）
        List<Map<String, Object>> days = new ArrayList<>();
        long totalDetect = 0, totalDefect = 0;
        int dataDays = 0, rateDays = 0;
        double rateSum = 0;
        String worstDate = null, bestDate = null;
        long worstDefect = -1, bestDefect = Long.MAX_VALUE;

        for (LocalDate d = monthStart; !d.isAfter(monthEnd); d = d.plusDays(1)) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("date", d.toString());
            boolean future = d.isAfter(today);
            boolean isToday = d.equals(today);
            Map<String, Object> row = isToday ? todayRow : byDate.get(d);
            if (future) {
                cell.put("isFuture", true);
                cell.put("hasData", false);
                cell.put("isToday", false);
                days.add(cell);
                continue;
            }
            if (row == null) {
                // 历史日没有快照（服务上线前 / 生成失败）→ 与「有行但 0 数据」区分开
                cell.put("isFuture", false);
                cell.put("isToday", isToday);
                cell.put("hasData", false);
                cell.put("missingSnapshot", true);
                days.add(cell);
                continue;
            }
            int detect = ((Number) row.getOrDefault("detectCount", 0)).intValue();
            int defect = ((Number) row.getOrDefault("defectCount", 0)).intValue();
            cell.put("isFuture", false);
            cell.put("isToday", isToday);
            cell.put("hasData", detect > 0);
            cell.put("detectCount", detect);
            cell.put("defectCount", defect);
            cell.put("defectRateKilo", detect > 0 ? row.get("defectRateKilo") : null);
            cell.put("cameraOnline", row.get("cameraOnline"));
            cell.put("cameraTotal", row.get("cameraTotal"));
            cell.put("tempAlarmCount", row.get("tempAlarmCount"));
            cell.put("sampleInsufficient", detect > 0 && detect < DailyReportRepository.MIN_RANK_SAMPLE);
            cell.put("dataSource", isToday ? "REALTIME" : "SNAPSHOT");

            long[] cum = cumByDate.get(d);
            long[] prevCum = cumByDate.get(d.minusDays(1));
            if (cum != null && prevCum != null) {
                long inspDelta = cum[0] - prevCum[0];
                long ngDelta = cum[1] - prevCum[1];
                if (inspDelta > 0 && ngDelta >= 0) {
                    cell.put("inspDelta", inspDelta);
                    cell.put("ngDelta", ngDelta);
                    cell.put("counterRateKilo", Math.round(ngDelta * 100000.0 / inspDelta) / 100.0);
                    rateSum += ngDelta * 1000.0 / inspDelta;
                    rateDays++;
                }
            }
            // 今天还没有「明天的基线」，差分自然不可用 → 上面没写就是 null，不额外解释

            if (detect > 0) {
                dataDays++;
                totalDetect += detect;
                totalDefect += defect;
                // 「最多/最少的一天」只评已完成的历史日：今天还在涨，拿它当「最差」会让
                // 厂长每天早上看到「今天最差」，随时间推移又自我修正 —— 没有决策价值。
                if (!isToday) {
                    if (defect > worstDefect) {
                        worstDefect = defect;
                        worstDate = d.toString();
                    }
                    if (defect < bestDefect) {
                        bestDefect = defect;
                        bestDate = d.toString();
                    }
                }
            }
            days.add(cell);
        }

        // ⑤ 产线胶囊 Tab：全厂在首位，其余只列有 REAL 相机的产线
        List<Map<String, Object>> lines = new ArrayList<>();
        Map<String, Object> plant = new LinkedHashMap<>();
        plant.put("lineId", 0);
        plant.put("lineName", "全厂");
        lines.add(plant);
        lines.addAll(dailyReportRepository.listRealLines());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dataDays", dataDays);
        summary.put("rateDays", rateDays);
        summary.put("totalDetect", totalDetect);
        summary.put("totalDefect", totalDefect);
        summary.put("avgCounterRateKilo", rateDays > 0 ? Math.round(rateSum / rateDays * 100.0) / 100.0 : null);
        summary.put("worstDate", worstDate);
        summary.put("worstDefect", worstDefect >= 0 ? worstDefect : null);
        summary.put("bestDate", bestDate);
        summary.put("bestDefect", worstDefect >= 0 ? bestDefect : null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("month", monthStart.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
        data.put("lineId", scope);
        data.put("lines", lines);
        data.put("days", days);
        data.put("summary", summary);
        data.put("caliber", CALIBER);
        return ResultVO.success(data);
    }

    // ==================== 单日维度明细（月历下方的四宫格） ====================

    /**
     * 单日某 scope 的维度明细：缺陷类型 TOP5 / 相机 TOP10 / 24 小时分布 / 异常摘要。
     *
     * <p>汇总指标取快照（历史日）或实时算（今天），明细从图片事实表算 —— 所以历史日
     * 超过 31 天归档期后 {@code detailsAvailable=false}，页面显示「明细已归档」而不是空图。
     */
    @GetMapping("/{date}/breakdown")
    public ResultVO<Map<String, Object>> breakdown(@PathVariable String date,
                                                   @RequestParam(required = false) Integer lineId) {
        LocalDate day = parseDate(date, null);
        if (day == null) {
            return ResultVO.fail("日期格式应为 yyyy-MM-dd");
        }
        if (day.isAfter(LocalDate.now())) {
            return ResultVO.fail("不能查询未来日期");
        }
        int scope = (lineId == null || lineId == 0) ? 0 : lineId;
        boolean realtime = day.equals(LocalDate.now());

        // ① 汇总行：历史日优先用快照（图片可能已归档，重算会得到 0），今天实时算
        Map<String, Object> kpi = null;
        String dataSource;
        if (realtime) {
            for (Map<String, Object> row : dailyReportRepository.computeDay(day, true)) {
                if (scope == ((Number) row.get("lineId")).intValue()) {
                    decorate(row, day, "REALTIME");
                    kpi = row;
                    break;
                }
            }
            dataSource = "REALTIME";
        } else {
            List<Map<String, Object>> rows = dailyReportRepository.listRange(day, day, scope);
            if (!rows.isEmpty()) {
                decorate(rows.get(0), day, "SNAPSHOT");
                kpi = rows.get(0);
            }
            dataSource = "SNAPSHOT";
        }

        Map<String, Object> details = dailyReportRepository.computeBreakdownDetails(day, scope);

        Map<String, Object> anomalies = new LinkedHashMap<>();
        int tempAlarm = kpi == null ? 0 : ((Number) kpi.getOrDefault("tempAlarmCount", 0)).intValue();
        anomalies.put("tempAlarmCount", tempAlarm);
        Object idle = details.remove("idleHours");
        anomalies.put("idleHours", idle);
        int online = kpi == null ? 0 : ((Number) kpi.getOrDefault("cameraOnline", 0)).intValue();
        int total = kpi == null ? 0 : ((Number) kpi.getOrDefault("cameraTotal", 0)).intValue();
        anomalies.put("offlineCameras", Math.max(total - online, 0));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportDate", day.toString());
        data.put("lineId", scope);
        data.put("lineName", kpi == null ? null : kpi.get("lineName"));
        data.put("dataSource", dataSource);
        data.put("kpi", kpi);
        data.put("missing", kpi == null);
        data.putAll(details);
        data.put("anomalies", anomalies);
        // 图片通道没有设备状态上报，「这些小时 0 上传」是最接近「停线/断网」的代理指标，
        // 口径必须写清楚：0 上传 ≠ 停线（可能只是这段时间没排产）。
        data.put("idleNote", "idleHours = 当日 0 上传的小时（当天只统计到当前小时）；"
                + "0 上传可能停线/相机断网，也可能只是没排产，需现场确认");
        data.put("caliber", CALIBER);
        return ResultVO.success(data);
    }

    // ==================== 补算（管理员） ====================

    /** 手工补算/重算某天（含今天）。幂等覆盖：重跑 DELETE+INSERT，不产生重复行。 */
    @PostMapping("/{date}/regenerate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResultVO<Map<String, Object>> regenerate(@PathVariable String date) {
        LocalDate day = parseDate(date, null);
        if (day == null) {
            return ResultVO.fail("日期格式应为 yyyy-MM-dd");
        }
        if (day.isAfter(LocalDate.now())) {
            return ResultVO.fail("不能补算未来日期");
        }
        // 补算历史日期不拍计数快照（当前计数≠那个日期的日终计数，拍下来差分必错）；
        // 补算当天可以拍（还没过日界，当前计数就是今天截至此刻的真值）。
        boolean ok = generationTask.generateFor(day, day.equals(LocalDate.now()));
        if (!ok) {
            return ResultVO.fail("补算失败，已落 DAILY_REPORT_FAILED 告警，请查看服务日志");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "补算完成");
        data.put("reportDate", day.toString());
        data.put("regenerated", true);
        data.put("rows", dailyReportRepository.listRange(day, day, null).size());
        return ResultVO.success(data);
    }

    // ==================== 导出 ====================

    /** 导出区间内日报（厂级 + 产线行一起导，每行标注产线名）。 */
    @GetMapping(value = "/export", produces = XLSX_MEDIA_TYPE)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String startDate,
                                         @RequestParam(required = false) String endDate,
                                         @RequestParam(required = false) Integer lineId) throws Exception {
        LocalDate end = parseDate(endDate, LocalDate.now());
        LocalDate start = parseDate(startDate, end.minusDays(29));
        if (start.isAfter(end)) {
            return ResponseEntity.badRequest().build();
        }
        LocalDate today = LocalDate.now();
        LocalDate snapshotEnd = end.isBefore(today) ? end : today.minusDays(1);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!snapshotEnd.isBefore(start)) {
            rows.addAll(dailyReportRepository.listRange(start, snapshotEnd, lineId));
        }
        if (!end.isBefore(today)) {
            for (Map<String, Object> row : dailyReportRepository.computeDay(today, true)) {
                if (lineId == null ? Integer.valueOf(0).equals(row.get("lineId"))
                        : lineId.equals(((Number) row.get("lineId")).intValue())) {
                    rows.add(row);
                }
            }
        }
        rows.sort((a, b) -> compareDateDesc(a, b));

        List<String> headers = Arrays.asList("日期", "产线", "检测总数(日增)", "缺陷数(日增)", "缺陷率‰(计数)",
                "缺陷图片数(通道)", "较前日%", "在线相机", "相机总数", "温度告警", "TOP1缺陷类型", "数据源");
        List<List<Object>> table = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            decorate(r, toLocalDate(r.get("reportDate")),
                    "REALTIME".equals(r.get("dataSource")) ? "REALTIME" : "SNAPSHOT");
            table.add(Arrays.asList(
                    String.valueOf(r.get("reportDate")),
                    r.get("lineName") == null ? (Integer.valueOf(0).equals(((Number) r.get("lineId")).intValue()) ? "全厂" : "") : r.get("lineName"),
                    r.get("inspDelta") == null ? "待积累" : r.get("inspDelta"),
                    r.get("ngDelta") == null ? "待积累" : r.get("ngDelta"),
                    r.get("counterRateKilo") == null ? "待积累" : r.get("counterRateKilo"),
                    r.get("detectCount"),
                    dayOverDayPercent(r),
                    r.get("cameraOnline"),
                    r.get("cameraTotal"),
                    r.get("tempAlarmCount"),
                    firstTopType(r.get("topDefectTypes")),
                    "REALTIME".equals(r.get("dataSource")) ? "实时" : "快照"));
        }
        byte[] bytes = writeWorkbook("产线统计日报", headers, table);
        String filename = "产线统计日报-" + start + "_" + end + ".xlsx";
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.parseMediaType(XLSX_MEDIA_TYPE));
        httpHeaders.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build());
        httpHeaders.setContentLength(bytes.length);
        return ResponseEntity.ok().headers(httpHeaders).body(bytes);
    }

    // ==================== 私有 ====================

    /** 给行补展示字段：环比（vs 前一天同 scope）、计数口径日增/真缺陷率、样本不足标记、dataSource */
    private void decorate(Map<String, Object> row, LocalDate day, String dataSource) {
        if (day == null) {
            // 防御：reportDate 缺失时只补 dataSource，不做依赖日期的环比/计数差分
            row.put("dataSource", dataSource);
            return;
        }
        row.put("reportDate", day.toString());
        row.put("dataSource", dataSource);
        int detect = ((Number) row.getOrDefault("detectCount", 0)).intValue();
        row.put("sampleInsufficient", detect < DailyReportRepository.MIN_RANK_SAMPLE);
        // 口径冻结：无数据（detectCount=0）→ 图片口径缺陷率强制 null，绝不显示 0‰。
        // 仓储层已按此口径计算，这里再做一道防线，防止后续改动破坏「无数据 ≠ 0」。
        if (detect == 0) {
            row.put("defectRateKilo", null);
        }
        // 计数口径：真缺陷率 = 心跳累计计数日增差分（2026-11-17 实测图片口径恒 1000‰，
        // 无区分度；计数口径从第二次快照起可用）。差分为负 = 相机计数器被重置 → 视为不可信，置 null。
        try {
            Integer lineId = ((Number) row.get("lineId")).intValue();
            boolean plantRow = lineId == 0;
            List<Map<String, Object>> prev = dailyReportRepository.listRange(
                    day.minusDays(1), day.minusDays(1), plantRow ? null : lineId);
            Map<String, Object> prevRow = null;
            for (Map<String, Object> p : prev) {
                if (Integer.valueOf(0).equals(((Number) p.get("lineId")).intValue()) == plantRow) {
                    prevRow = p;
                    break;
                }
            }
            Object prevRate = prevRow == null ? null : prevRow.get("defectRateKilo");
            row.put("prevRateKilo", prevRate);
            Object rate = row.get("defectRateKilo");
            row.put("dayOverDayRate", rate instanceof Number && prevRate instanceof Number
                    ? Math.round((((Number) rate).doubleValue() - ((Number) prevRate).doubleValue())
                            * 10000.0 / ((Number) prevRate).doubleValue()) / 100.0
                    : null);

            Long inspCum = longOrNull(row.get("inspTotalCum"));
            Long ngCum = longOrNull(row.get("ngTotalCum"));
            Long prevInsp = prevRow == null ? null : longOrNull(prevRow.get("inspTotalCum"));
            Long prevNg = prevRow == null ? null : longOrNull(prevRow.get("ngTotalCum"));
            if (inspCum != null && prevInsp != null && ngCum != null && prevNg != null) {
                long inspDelta = inspCum - prevInsp;
                long ngDelta = ngCum - prevNg;
                if (inspDelta >= 0 && ngDelta >= 0 && inspDelta > 0) {
                    row.put("inspDelta", inspDelta);
                    row.put("ngDelta", ngDelta);
                    row.put("counterRateKilo", Math.round(ngDelta * 100000.0 / inspDelta) / 100.0);
                } else {
                    putCounterUnavailable(row);
                }
            } else {
                putCounterUnavailable(row);
            }
        } catch (Exception e) {
            log.warn("[产线日报] 补充环比/计数口径失败（不影响主指标）: {}", e.getMessage());
            row.put("prevRateKilo", null);
            row.put("dayOverDayRate", null);
            putCounterUnavailable(row);
        }
    }

    private static void putCounterUnavailable(Map<String, Object> row) {
        row.put("inspDelta", null);
        row.put("ngDelta", null);
        row.put("counterRateKilo", null);
    }

    private static List<Map<String, Object>> parseTopTypes(Object json) {
        List<Map<String, Object>> list = new ArrayList<>();
        String s = json == null ? null : String.valueOf(json).trim();
        if (s == null || s.length() < 2) return list;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            List<?> raw = om.readValue(s, List.class);
            for (Object o : raw) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    list.add(m);
                }
            }
        } catch (Exception ignore) {
            // JSON 损坏时退化为空列表，详情页少显示 TOP 类型，不影响主指标
        }
        return list;
    }

    private static String firstTopType(Object json) {
        List<Map<String, Object>> list = parseTopTypes(json);
        if (list.isEmpty()) return "";
        return list.get(0).get("type") + " (" + list.get(0).get("count") + ")";
    }

    private Object dayOverDayPercent(Map<String, Object> row) {
        Object rate = row.get("dayOverDayRate");
        return rate == null ? "" : rate + "%";
    }

    private static int compareDateDesc(Map<String, Object> a, Map<String, Object> b) {
        LocalDate da = toLocalDate(a.get("reportDate"));
        LocalDate db = toLocalDate(b.get("reportDate"));
        int byDate = db.compareTo(da);
        if (byDate != 0) return byDate;
        return Integer.compare(((Number) a.get("lineId")).intValue(),
                ((Number) b.get("lineId")).intValue());
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate();
        if (value instanceof LocalDate) return (LocalDate) value;
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    private static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            return LocalDate.parse(value.trim().substring(0, 10));
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    /** yyyy-MM → 该月 1 日；格式不对或缺失时返回当月 1 日（月历永远能出图） */
    private static LocalDate parseMonth(String value) {
        LocalDate today = LocalDate.now();
        if (value == null || value.trim().isEmpty()) return today.withDayOfMonth(1);
        String v = value.trim();
        try {
            if (v.length() >= 7) {
                return LocalDate.parse(v.substring(0, 7) + "-01");
            }
        } catch (DateTimeParseException ignore) {
            // 落到当月
        }
        return today.withDayOfMonth(1);
    }

    private static Long longOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- 与 LineAnalyticsController.writeWorkbook 同构（列数少，独立小实现，避免为 3 列差异抽公共类） ----

    private byte[] writeWorkbook(String sheetName, List<String> headers, List<List<Object>> rows) throws Exception {
        try (org.apache.poi.ss.usermodel.Workbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet(
                    sheetName.length() > 31 ? sheetName.substring(0, 31) : sheetName);
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            for (int rIdx = 0; rIdx < rows.size(); rIdx++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(rIdx + 1);
                List<Object> values = rows.get(rIdx);
                for (int c = 0; c < values.size(); c++) {
                    org.apache.poi.ss.usermodel.Cell cell = row.createCell(c);
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
}
