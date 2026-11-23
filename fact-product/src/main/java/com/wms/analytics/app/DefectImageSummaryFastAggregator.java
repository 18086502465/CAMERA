package com.wms.analytics.app;

import com.wms.analytics.infra.repository.LineAnalyticsQueryRepository;
import com.wms.analytics.infra.repository.LineAnalyticsQueryRepository.CameraDimRow;
import com.wms.analytics.infra.repository.LineAnalyticsQueryRepository.ImageBoxRow;
import com.wms.analytics.infra.repository.LineAnalyticsQueryRepository.LineDimRow;
import com.wms.analytics.infra.repository.LineAnalyticsQueryRepository.StationDimRow;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 图片事实表统计的<b>单遍内存聚合</b>（性能优化 2026-11-21）。
 *
 * <p><b>背景</b>：原 {@code buildDefectImageSummary} 一次响应串行跑 12+ 条 SQL，
 * 每条都独立扫一遍时间窗内全部图片行（现场 7 天窗 ≈ 6.6 万行、覆盖全表 90%，
 * 优化器选全表扫是正确选择，索引救不了），且每行还要回表 {@code dim_camera}，
 * box 类查询再多 66k 次回表 —— 冷查实测 ~3.9s；页面挂载时全局汇总 + N 个
 * widget（不同 dim 参数各 miss 缓存）各跑一遍 → 首屏 8s+。
 *
 * <p><b>做法</b>：一条 SQL 取回窗口内 {@code defect_image_file LEFT JOIN defect_image_box}
 * 展平行（~6.9 万行、百毫秒级）+ 一次性载入 {@code dim_camera / dim_workstation /
 * dim_production_line} 小维表（现场 32 行），其余 12 个数据块全部在内存聚合。
 * 冷查 ~3.9s → 预期 ~0.4s；配合既有 60s 结果缓存，跨分钟重算也在亚秒级。
 *
 * <p><b>口径对齐（逐块与 SQL 路径等价，2026-11-21 现场 13 case 基线逐字节比对）</b>：
 * <ul>
 *   <li>行乘法：box 类块（排行/透视/严重度/热力图/班次）按「图片×框」展开
 *       （INNER 语义：无框图片不参与）；KPI/趋势/日历/工位/相机排行按图片一行；</li>
 *   <li>筛选：camera 维筛选在维表行缺失或维度不匹配时排除该图
 *       （SQL 里 LEFT JOIN + WHERE c.xxx = ? 的等价语义）；defectType 用
 *       EXISTS 精确匹配（大小写不敏感对齐 CI 排序规则）；shift 按
 *       TIME(capture_time) 窗口判断（支持跨天夜班 OR）；</li>
 *   <li>日历块固定近 30 天窗、用<b>原始</b> defectType；主块用 effectiveDefectType
 *       （defectTypeId 映射后的现场上报名）—— 与 SQL 路径逐处一致；</li>
 *   <li>severity 只作用于排行/透视/严重度分布三块（与 SQL 路径一致）；</li>
 *   <li>排序与 LIMIT：排行 20 / 透视 30 照旧截断；★热力图与班次对比返回<b>完整数据</b>
 *       （SQL 路径因「防大结果集拖慢查询」保留 LIMIT 360/12 + 截断上报，即 R-1/R-2 的
 *       诚实降级；内存聚合没有该约束，直接给出全量 —— 这是 R-1/R-2 的终态，
 *       截断标记如实为 false）。所有排序对并列计数补确定性 tie-break
 *       （SQL 对并列顺序未定义，基线比对时会出现纯顺序差异）。</li>
 * </ul>
 *
 * <p><b>回退</b>：主窗口过滤后无图片（SQL 路径此时会走聚合表兜底分支，行为不同，
 * 必须让位）或聚合过程抛异常时返回 null / 由调用方回退 SQL 路径。
 */
@Component
public class DefectImageSummaryFastAggregator {

    /** 分桶粒度自适应阈值（与 Controller 的 TREND_DAILY_BUCKET_THRESHOLD_DAYS 保持一致）。 */
    private static final int TREND_DAILY_BUCKET_THRESHOLD_DAYS = 3;

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final LineAnalyticsQueryRepository queryRepository;

    public DefectImageSummaryFastAggregator(LineAnalyticsQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    /** 内存图片聚合单元（还原「图片 → 框列表」结构）。 */
    private static final class Img {
        long imageId;
        String cameraCode;
        LocalDateTime captureTime;
        long defectCount;
        long highCount;
        long mediumCount;
        long lowCount;
        CameraDimRow cam;
        final List<ImageBoxRow> boxes = new ArrayList<>();
    }

    /**
     * 单遍聚合入口。
     *
     * @param effectiveDefectType 主块用的缺陷类型（defectTypeId 映射后的现场上报名，可为 null）
     * @param rawDefectType       日历/环比块用的原始 defectType 参数（与 SQL 路径一致）
     * @param shiftFilter         resolveShift(shiftId) 结果（空 Map = 不筛选；缺失班次由调用方抛 400）
     * @param shiftWindows        resolveShiftWindows() 结果（dayStart/dayEnd，班次分桶用）
     * @param ignoredFilters      诚实降级上报（调用方已算好，保证与 SQL 路径同源）
     * @return 完整 data 块集合；主窗口过滤后无图片时返回 null（调用方回退 SQL 路径）
     */
    public Map<String, Object> build(LocalDateTime start, LocalDateTime end,
                                     Long factoryId, Long lineId, List<Long> lineIds,
                                     Long workstationId, List<Long> workstationIds,
                                     Long cameraId,
                                     String severity, String effectiveDefectType, String rawDefectType,
                                     Long shiftId, Map<String, Object> shiftFilter,
                                     String dim, Map<String, Object> shiftWindows,
                                     List<String> ignoredFilters, String ignoredFilterMessage) {

        // ---- 加载窗口 = 主窗 ∪ 日历窗(近30天) ∪ 环比窗 的并集 ----
        LocalDateTime calStart = end.toLocalDate().minusDays(29).atStartOfDay();
        LocalDateTime loadStart = start.isBefore(calStart) ? start : calStart;
        Duration span = Duration.between(start, end);
        if (!span.isZero() && !span.isNegative()) {
            LocalDateTime prevStart = start.minus(span);
            if (prevStart.isBefore(loadStart)) loadStart = prevStart;
        }

        List<ImageBoxRow> rows = queryRepository.fetchImageBoxRows(
                java.sql.Timestamp.valueOf(loadStart), java.sql.Timestamp.valueOf(end));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, CameraDimRow> cams = queryRepository.fetchCameraDims();
        Map<Long, StationDimRow> stations = queryRepository.fetchStationDims();
        Map<Long, LineDimRow> lines = queryRepository.fetchLineDims();

        // ---- 还原「图片 → 框列表」结构（LEFT JOIN 行乘法 → 分组）----
        Map<Long, Img> byId = new LinkedHashMap<>();
        for (ImageBoxRow r : rows) {
            Img img = byId.get(r.imageId);
            if (img == null) {
                img = new Img();
                img.imageId = r.imageId;
                img.cameraCode = r.cameraCode;
                img.captureTime = r.captureTime;
                img.defectCount = r.defectCount;
                img.highCount = r.highCount;
                img.mediumCount = r.mediumCount;
                img.lowCount = r.lowCount;
                img.cam = r.cameraCode == null ? null : cams.get(r.cameraCode);
                byId.put(r.imageId, img);
            }
            if (r.boxId != null) {
                img.boxes.add(r);
            }
        }

        // ---- 筛选谓词（与 buildImageWhere 的 SQL 语义逐条对齐）----
        Set<Long> lineIdSet = idUnion(lineId, lineIds);
        Set<Long> wsIdSet = idUnion(workstationId, workstationIds);
        boolean camFilterActive = factoryId != null || !lineIdSet.isEmpty() || !wsIdSet.isEmpty() || cameraId != null;
        Predicate<Img> camOk = img -> {
            if (!camFilterActive) return true;   // 无维筛选：LEFT JOIN 不丢图
            CameraDimRow c = img.cam;
            if (c == null) return false;         // WHERE c.xxx = ? 隐式排除无维行
            if (factoryId != null && !factoryId.equals(c.factoryId)) return false;
            if (!lineIdSet.isEmpty() && (c.lineId == null || !lineIdSet.contains(c.lineId))) return false;
            if (!wsIdSet.isEmpty() && (c.workstationId == null || !wsIdSet.contains(c.workstationId))) return false;
            if (cameraId != null && !cameraId.equals(c.cameraId)) return false;
            return true;
        };
        // shift 谓词（SQL：TIME(capture_time) 窗口；跨天夜班 OR）
        LocalTime shiftS = null;
        LocalTime shiftE = null;
        boolean shiftCrossDay = false;
        boolean shiftActive = shiftId != null && shiftFilter != null && !shiftFilter.isEmpty();
        if (shiftActive) {
            shiftS = parseHhmmss(shiftFilter.get("startTime"));
            shiftE = parseHhmmss(shiftFilter.get("endTime"));
            shiftCrossDay = asLong(shiftFilter.get("crossDay")) == 1L;
        }
        final LocalTime fsS = shiftS;
        final LocalTime fsE = shiftE;
        final boolean fCross = shiftCrossDay;
        final boolean fShiftActive = shiftActive;
        Predicate<LocalTime> shiftOk = t -> {
            if (!fShiftActive) return true;
            if (t == null) return false;
            return fCross ? (t.compareTo(fsS) >= 0 || t.compareTo(fsE) < 0)
                          : (t.compareTo(fsS) >= 0 && t.compareTo(fsE) < 0);
        };
        // defectType EXISTS 谓词（CI 排序规则 → equalsIgnoreCase；参数已 trim）
        final String mainType = trimToNull(effectiveDefectType);
        final String calType = trimToNull(rawDefectType);
        Predicate<Img> mainPred = img -> {
            if (img.captureTime.isBefore(start) || img.captureTime.isAfter(end)) return false;
            if (!camOk.test(img)) return false;
            if (mainType != null && !hasBoxType(img, mainType)) return false;
            if (fShiftActive && !shiftOk.test(img.captureTime.toLocalTime())) return false;
            return true;
        };

        // ---- 主窗口过滤集（空 → 让位 SQL 路径的聚合表兜底分支）----
        List<Img> main = new ArrayList<>();
        for (Img img : byId.values()) {
            if (mainPred.test(img)) main.add(img);
        }
        if (main.isEmpty()) {
            return null;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ignoredFilters", ignoredFilters);
        if (ignoredFilterMessage != null) {
            data.put("ignoredFilterMessage", ignoredFilterMessage);
        }

        // ==================== KPI ====================
        long defectCount = 0;
        long highCount = 0;
        long mediumCount = 0;
        long lowCount = 0;
        Set<Long> camIds = new LinkedHashSet<>();
        Set<Long> wsIds = new LinkedHashSet<>();
        for (Img img : main) {
            defectCount += img.defectCount;
            highCount += img.highCount;
            mediumCount += img.mediumCount;
            lowCount += img.lowCount;
            if (img.cam != null) {
                if (img.cam.cameraId != null) camIds.add(img.cam.cameraId);
                if (img.cam.workstationId != null) wsIds.add(img.cam.workstationId);
            }
        }
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("totalCount", (long) main.size());
        kpi.put("defectCount", defectCount);
        kpi.put("okCount", 0L);
        kpi.put("ngCount", (long) main.size());
        kpi.put("highCount", highCount);
        kpi.put("mediumCount", mediumCount);
        kpi.put("lowCount", lowCount);
        kpi.put("cameraCount", (long) camIds.size());
        kpi.put("workstationCount", (long) wsIds.size());
        kpi.put("yieldRate", null);   // 图片通道无良品样本，良率不可计算
        kpi.put("ngRate", null);
        kpi.put("yieldRateAvailable", false);
        appendWindowCompareFast(kpi, start, end, span, byId.values(), camOk, calType, shiftOk, fShiftActive);
        data.put("kpi", kpi);

        // ==================== 趋势 ====================
        boolean dayBucket = span.toDays() >= TREND_DAILY_BUCKET_THRESHOLD_DAYS;
        data.put("trendGranularity", dayBucket ? "DAY" : "HOUR");
        data.put("trend", buildTrend(main, dim, dayBucket, start, end, shiftWindows, cams, stations, lines));

        // ==================== 日历（固定近 30 天窗 + 原始 defectType）====================
        data.put("dailyCalendar", buildDailyCalendar(byId.values(), calStart, end, camOk, calType, shiftOk, fShiftActive));

        // ==================== 缺陷类型排行 ====================
        String sevUpper = severity != null && !severity.trim().isEmpty()
                ? severity.trim().toUpperCase(Locale.ROOT) : null;
        data.put("defectRanking", buildDefectRanking(main, dim, sevUpper, shiftWindows, cams, stations, lines));

        // ==================== 工位 / 相机排行 ====================
        data.put("stationRanking", buildStationRanking(main, stations));
        data.put("cameraRanking", buildCameraRanking(main));

        // ==================== 透视 / 严重度分布 ====================
        data.put("defectPivot", buildPivot(main, sevUpper));
        data.put("severityDistribution", buildSeverityDistribution(main, sevUpper));

        // ==================== 热力图（无 severity 约束）====================
        Object[] heat = buildHeatmap(main);
        data.put("hourlyHeatmap", heat[0]);
        data.put("hourlyHeatmapTotalCombos", heat[1]);
        data.put("hourlyHeatmapTruncated", heat[2]);
        data.put("hourlyHeatmapTruncatedCount", heat[3]);

        // ==================== 班次对比（无 severity 约束）====================
        String dayStart = String.valueOf(shiftWindows.get("dayStart"));
        String dayEnd = String.valueOf(shiftWindows.get("dayEnd"));
        LocalTime dayS = parseHhmmss(dayStart);
        LocalTime dayE = parseHhmmss(dayEnd);
        Object[] shiftCmp = buildShiftCompare(main, dayS, dayE);
        data.put("shiftCompare", shiftCmp[0]);
        data.put("shiftCompareTotalTypes", shiftCmp[1]);
        data.put("shiftCompareTruncated", shiftCmp[2]);
        data.put("shiftCompareTruncatedCount", shiftCmp[3]);
        data.put("shiftNames", shiftWindows);

        return data;
    }

    // ==================== 环比（前移等长窗口，图片计数）====================

    private void appendWindowCompareFast(Map<String, Object> kpi, LocalDateTime start, LocalDateTime end,
                                         Duration span, Iterable<Img> all, Predicate<Img> camOk,
                                         String rawType, Predicate<LocalTime> shiftOk, boolean shiftActive) {
        kpi.put("prevWindowCount", null);
        kpi.put("dayOverDayRate", null);
        kpi.put("dayOverDayLabel", null);
        try {
            if (span.isZero() || span.isNegative()) {
                return;
            }
            LocalDateTime prevStart = start.minus(span);
            Predicate<Img> prevPred = img -> {
                if (img.captureTime.isBefore(prevStart) || img.captureTime.isAfter(start)) return false;
                if (!camOk.test(img)) return false;
                if (rawType != null && !hasBoxType(img, rawType)) return false;
                if (shiftActive && !shiftOk.test(img.captureTime.toLocalTime())) return false;
                return true;
            };
            long prevCount = 0;
            for (Img img : all) {
                if (prevPred.test(img)) prevCount++;
            }
            kpi.put("prevWindowCount", prevCount);
            long current = ((Number) kpi.get("totalCount")).longValue();
            if (prevCount > 0) {
                kpi.put("dayOverDayRate", Math.round((current - prevCount) * 10000.0 / prevCount) / 100.0);
            }
            long days = span.toDays();
            long hours = span.toHours();
            kpi.put("dayOverDayLabel", days >= 1 ? "较前 " + days + " 天" : (hours >= 1 ? "较前 " + hours + " 小时" : "较前时段"));
        } catch (Exception ex) {
            // 环比是增强信息，任何失败都不能影响主 KPI（与 SQL 路径的容错口径一致）
        }
    }

    // ==================== 趋势 ====================

    private List<Map<String, Object>> buildTrend(List<Img> main, String dim, boolean dayBucket,
                                                 LocalDateTime start, LocalDateTime end,
                                                 Map<String, Object> shiftWindows,
                                                 Map<String, CameraDimRow> cams,
                                                 Map<Long, StationDimRow> stations,
                                                 Map<Long, LineDimRow> lines) {
        String dimToken = recognizedDim(dim);
        boolean needBoxJoin = "defectType".equals(dimToken) || "severity".equals(dimToken);
        LocalTime dayS = parseHhmmss(shiftWindows.get("dayStart"));
        LocalTime dayE = parseHhmmss(shiftWindows.get("dayEnd"));

        // key: [bucket, seriesKey]（无拆分时 seriesKey 恒为 ""）
        Map<String, long[]> agg = new LinkedHashMap<>();
        Map<String, String[]> keyOrder = new LinkedHashMap<>(); // key -> {bucket, seriesKey}
        for (Img img : main) {
            if (img.captureTime == null) continue;
            String bucket = dayBucket ? DAY_FMT.format(img.captureTime)
                    : String.format("%04d-%02d-%02d %02d:00:00",
                            img.captureTime.getYear(), img.captureTime.getMonthValue(),
                            img.captureTime.getDayOfMonth(), img.captureTime.getHour());
            if (dimToken == null) {
                accumulateTrendRow(agg, keyOrder, bucket, "", img.defectCount, 1);
            } else if (needBoxJoin) {
                // LEFT JOIN box：无框图片产出一行（seriesKey = '未知'/'UNKNOWN'）
                if (img.boxes.isEmpty()) {
                    String sk = "defectType".equals(dimToken) ? "未知" : "UNKNOWN";
                    accumulateTrendRow(agg, keyOrder, bucket, sk, img.defectCount, 1);
                } else {
                    for (ImageBoxRow b : img.boxes) {
                        String sk = "defectType".equals(dimToken)
                                ? (b.boxType == null ? "未知" : b.boxType)
                                : (b.boxSeverity == null ? "UNKNOWN" : b.boxSeverity);
                        accumulateTrendRow(agg, keyOrder, bucket, sk, img.defectCount, 1);
                    }
                }
            } else {
                String sk = dimSeriesKey(img, null, dimToken, cams, stations, lines, dayS, dayE);
                accumulateTrendRow(agg, keyOrder, bucket, sk, img.defectCount, 1);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            String[] ks = keyOrder.get(e.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timeBucket", ks[0]);
            if (dimToken != null) row.put("seriesKey", ks[1]);
            row.put("totalCount", e.getValue()[0]);
            row.put("okCount", 0L);
            row.put("ngCount", e.getValue()[0]);
            row.put("defectCount", e.getValue()[1]);
            row.put("yieldRate", null);
            out.add(row);
        }
        // ORDER BY timeBucket, seriesKey
        out.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("timeBucket")))
                .thenComparing(r -> String.valueOf(r.getOrDefault("seriesKey", ""))));
        return out;
    }

    private void accumulateTrendRow(Map<String, long[]> agg, Map<String, String[]> keyOrder,
                                    String bucket, String seriesKey, long defectCount, int images) {
        String key = bucket + "\u0001" + seriesKey;
        long[] v = agg.get(key);
        if (v == null) {
            agg.put(key, new long[]{images, defectCount});
            keyOrder.put(key, new String[]{bucket, seriesKey});
        } else {
            v[0] += images;
            v[1] += defectCount;
        }
    }

    /** 维度 seriesKey（与 resolveDimSplit 的 COALESCE 表达式逐条对齐）。 */
    private String dimSeriesKey(Img img, ImageBoxRow box, String dimToken,
                                Map<String, CameraDimRow> cams,
                                Map<Long, StationDimRow> stations,
                                Map<Long, LineDimRow> lines,
                                LocalTime dayS, LocalTime dayE) {
        CameraDimRow c = img.cam;
        switch (dimToken) {
            case "camera":
                // COALESCE(NULLIF(c.camera_code,''), c.camera_name, f.camera_id)
                if (c != null && notBlank(c.cameraCode)) return c.cameraCode;
                if (c != null && notBlank(c.cameraName)) return c.cameraName;
                return img.cameraCode;
            case "workstation": {
                // COALESCE(NULLIF(w.station_name,''), CONCAT('工位#', IFNULL(c.workstation_id,0)))
                Long wid = c == null ? null : c.workstationId;
                StationDimRow w = wid == null ? null : stations.get(wid);
                if (w != null && notBlank(w.stationName)) return w.stationName;
                return "工位#" + (wid == null ? 0L : wid);
            }
            case "line": {
                // COALESCE(NULLIF(l.line_name,''), l.line_code, CONCAT('产线#', IFNULL(c.line_id,0)))
                Long lid = c == null ? null : c.lineId;
                LineDimRow l = lid == null ? null : lines.get(lid);
                if (l != null && notBlank(l.lineName)) return l.lineName;
                if (l != null && notBlank(l.lineCode)) return l.lineCode;
                return "产线#" + (lid == null ? 0L : lid);
            }
            case "defectType":
                return box == null ? "未知" : (box.boxType == null ? "未知" : box.boxType);
            case "severity":
                return box == null ? "UNKNOWN" : (box.boxSeverity == null ? "UNKNOWN" : box.boxSeverity);
            case "shift":
                return inWindow(img.captureTime.toLocalTime(), dayS, dayE) ? "白班" : "夜班";
            default:
                return "";
        }
    }

    // ==================== 日历 ====================

    private List<Map<String, Object>> buildDailyCalendar(Iterable<Img> all, LocalDateTime calStart,
                                                         LocalDateTime end, Predicate<Img> camOk,
                                                         String calType, Predicate<LocalTime> shiftOk,
                                                         boolean shiftActive) {
        Map<String, long[]> agg = new LinkedHashMap<>();   // dayKey -> [imageCount, defectCount]
        Map<String, Set<Long>> camPerDay = new LinkedHashMap<>();
        for (Img img : all) {
            if (img.captureTime.isBefore(calStart) || img.captureTime.isAfter(end)) continue;
            if (!camOk.test(img)) continue;
            if (calType != null && !hasBoxType(img, calType)) continue;
            if (shiftActive && !shiftOk.test(img.captureTime.toLocalTime())) continue;
            String day = DAY_FMT.format(img.captureTime);
            long[] v = agg.get(day);
            if (v == null) {
                agg.put(day, new long[]{1, img.defectCount});
            } else {
                v[0]++;
                v[1] += img.defectCount;
            }
            if (img.cam != null && img.cam.cameraId != null) {
                camPerDay.computeIfAbsent(day, k -> new LinkedHashSet<>()).add(img.cam.cameraId);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dayKey", e.getKey());
            row.put("imageCount", e.getValue()[0]);
            row.put("defectCount", e.getValue()[1]);
            Set<Long> cs = camPerDay.get(e.getKey());
            row.put("cameraCount", (long) (cs == null ? 0 : cs.size()));
            out.add(row);
        }
        out.sort(Comparator.comparing(r -> String.valueOf(r.get("dayKey"))));
        return out;
    }

    // ==================== 缺陷类型排行 ====================

    private List<Map<String, Object>> buildDefectRanking(List<Img> main, String dim, String sevUpper,
                                                         Map<String, Object> shiftWindows,
                                                         Map<String, CameraDimRow> cams,
                                                         Map<Long, StationDimRow> stations,
                                                         Map<Long, LineDimRow> lines) {
        String dimToken = recognizedDim(dim);
        LocalTime dayS = parseHhmmss(shiftWindows.get("dayStart"));
        LocalTime dayE = parseHhmmss(shiftWindows.get("dayEnd"));

        // SQL 始终 INNER JOIN box；GROUP BY = split==null ? (type,severity) : dimExpr；severity 维过滤照常
        Map<String, Object[]> agg = new LinkedHashMap<>(); // key -> {name, severity(输出), count}
        for (Img img : main) {
            for (ImageBoxRow b : img.boxes) {
                if (sevUpper != null && !eqCi(b.boxSeverity, sevUpper)) continue;
                String name;
                String sevOut;
                if (dimToken == null) {
                    name = b.boxType;
                    sevOut = b.boxSeverity;
                } else {
                    name = dimSeriesKey(img, b, dimToken, cams, stations, lines, dayS, dayE);
                    sevOut = null; // grouped 排行 SELECT NULL AS severity
                }
                String key = String.valueOf(name) + "\u0001" + String.valueOf(sevOut);
                Object[] v = agg.get(key);
                if (v == null) {
                    agg.put(key, new Object[]{name, sevOut, 1L});
                } else {
                    v[2] = (long) v[2] + 1L;
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] v : agg.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("defectName", v[0]);
            row.put("defectCode", v[0]);
            row.put("defectTypeId", 0L);
            row.put("severity", v[1]);
            row.put("defectCount", v[2]);
            out.add(row);
        }
        out.sort(Comparator.comparingLong((Map<String, Object> r) -> ((Number) r.get("defectCount")).longValue()).reversed()
                // 并列计数 tie-break（SQL 未定义顺序，这里取确定性字典序）
                .thenComparing(r -> String.valueOf(r.get("defectName")))
                .thenComparing(r -> String.valueOf(r.getOrDefault("severity", ""))));
        // 2026-11-23 决策 #9：现场实测 37 种缺陷类型，Top20 会截掉 17 种。
        // 主路径数据仅几十行，全量返回无性能问题；前端自行处理高度/滚动。
        return out;
    }

    // ==================== 工位 / 相机排行 ====================

    private List<Map<String, Object>> buildStationRanking(List<Img> main, Map<Long, StationDimRow> stations) {
        // GROUP BY c.workstation_id, w.station_name（键用 wid 即可——SQL 按两列分组，
        // 同 wid 的 station_name 唯一（维表主键），合并安全）
        Map<Long, long[]> agg = new LinkedHashMap<>();
        for (Img img : main) {
            Long wid = img.cam == null ? null : img.cam.workstationId;
            long[] v = agg.get(wid);
            if (v == null) {
                agg.put(wid, new long[]{1, img.defectCount});
            } else {
                v[0]++;
                v[1] += img.defectCount;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : agg.entrySet()) {
            Long wid = e.getKey();
            StationDimRow w = wid == null ? null : stations.get(wid);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workstationId", wid);
            row.put("workstationName", w == null ? null : w.stationName);
            row.put("totalCount", e.getValue()[0]);
            row.put("okCount", 0L);
            row.put("ngCount", e.getValue()[0]);
            row.put("defectCount", e.getValue()[1]);
            row.put("yieldRate", 0L);
            out.add(row);
        }
        // ORDER BY ngCount DESC, totalCount DESC（2026-11-23 取消 Top20 截断，见决策 #9）
        out.sort(Comparator.comparingLong((Map<String, Object> r) -> ((Number) r.get("ngCount")).longValue()).reversed()
                .thenComparing(r -> ((Number) r.get("totalCount")).longValue(), Comparator.reverseOrder()));
        return out;
    }

    private List<Map<String, Object>> buildCameraRanking(List<Img> main) {
        // GROUP BY c.camera_id, c.camera_name, c.camera_code（同一 cam 行三元组唯一；cam 缺失 = 全 NULL 组）
        Map<String, Object[]> agg = new LinkedHashMap<>();  // key -> {cameraId, cameraName, cameraCode, [count, defect]}
        for (Img img : main) {
            CameraDimRow c = img.cam;
            Long cid = c == null ? null : c.cameraId;
            String cname = c == null ? null : c.cameraName;
            String ccode = c == null ? null : c.cameraCode;
            String key = cid + "\u0001" + cname + "\u0001" + ccode;
            Object[] v = agg.get(key);
            if (v == null) {
                agg.put(key, new Object[]{cid, cname, ccode, new long[]{1, img.defectCount}});
            } else {
                long[] cnt = (long[]) v[3];
                cnt[0]++;
                cnt[1] += img.defectCount;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] v : agg.values()) {
            long[] cnt = (long[]) v[3];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cameraId", v[0]);
            row.put("cameraName", v[1]);
            row.put("cameraCode", v[2]);
            row.put("totalCount", cnt[0]);
            row.put("okCount", 0L);
            row.put("ngCount", cnt[0]);
            row.put("defectCount", cnt[1]);
            row.put("yieldRate", 0L);
            out.add(row);
        }
        out.sort(Comparator.comparingLong((Map<String, Object> r) -> ((Number) r.get("ngCount")).longValue()).reversed()
                .thenComparing(r -> ((Number) r.get("totalCount")).longValue(), Comparator.reverseOrder()));
        // 2026-11-23 决策 #9：相机排行取消 Top20 截断（现场 36 台，18 台有数据）
        return out;
    }

    // ==================== 透视 / 严重度分布 ====================

    private List<Map<String, Object>> buildPivot(List<Img> main, String sevUpper) {
        Map<String, long[]> agg = new LinkedHashMap<>();
        for (Img img : main) {
            for (ImageBoxRow b : img.boxes) {
                if (sevUpper != null && !eqCi(b.boxSeverity, sevUpper)) continue;
                long[] v = agg.get(b.boxType);
                if (v == null) agg.put(b.boxType, new long[]{1});
                else v[0]++;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", 0L);
            row.put("defectTypeId", 0L);
            row.put("defectCode", e.getKey());
            row.put("defectName", e.getKey());
            row.put("defectCount", e.getValue()[0]);
            out.add(row);
        }
        out.sort(Comparator.comparingLong((Map<String, Object> r) -> ((Number) r.get("defectCount")).longValue()).reversed()
                .thenComparing(r -> String.valueOf(r.get("defectName"))));
        return out.size() > 30 ? new ArrayList<>(out.subList(0, 30)) : out;
    }

    private List<Map<String, Object>> buildSeverityDistribution(List<Img> main, String sevUpper) {
        Map<String, long[]> agg = new LinkedHashMap<>();
        for (Img img : main) {
            for (ImageBoxRow b : img.boxes) {
                if (sevUpper != null && !eqCi(b.boxSeverity, sevUpper)) continue;
                String sev = b.boxSeverity == null ? "UNKNOWN" : b.boxSeverity.toUpperCase(Locale.ROOT);
                long[] v = agg.get(sev);
                if (v == null) agg.put(sev, new long[]{1});
                else v[0]++;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("severity", e.getKey());
            row.put("defectCount", e.getValue()[0]);
            out.add(row);
        }
        out.sort(Comparator.comparingLong((Map<String, Object> r) -> ((Number) r.get("defectCount")).longValue()).reversed());
        return out;
    }

    // ==================== 热力图 ====================

    /** 返回 [rows, totalCombos, truncated, truncatedCount]。无 severity 约束（与 SQL 路径一致）。 */
    private Object[] buildHeatmap(List<Img> main) {
        Map<String, Object[]> agg = new LinkedHashMap<>(); // key -> {hour, type, count}
        for (Img img : main) {
            int hour = img.captureTime.getHour();
            for (ImageBoxRow b : img.boxes) {
                String key = hour + "\u0001" + String.valueOf(b.boxType);
                Object[] v = agg.get(key);
                if (v == null) agg.put(key, new Object[]{hour, b.boxType, 1L});
                else v[2] = (long) v[2] + 1L;
            }
        }
        int totalCombos = agg.size();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] v : agg.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hourOfDay", v[0]);
            row.put("defectType", v[1]);
            row.put("defectCount", v[2]);
            out.add(row);
        }
        // ORDER BY defectCount DESC, hourOfDay ASC（并列再按类型名定序）；
        // ★ 内存聚合不设 LIMIT 360 —— 完整返回（见类注释 R-1 终态）
        out.sort(Comparator.comparingLong((Map<String, Object> r) -> ((Number) r.get("defectCount")).longValue()).reversed()
                .thenComparing(r -> ((Number) r.get("hourOfDay")).intValue())
                .thenComparing(r -> String.valueOf(r.get("defectType"))));
        boolean truncated = totalCombos > out.size();
        long truncatedCount = Math.max(totalCombos - out.size(), 0);
        return new Object[]{out, (long) totalCombos, truncated, truncatedCount};
    }

    // ==================== 班次对比 ====================

    /** 返回 [rows, totalTypes, truncated, truncatedCount]。无 severity 约束（与 SQL 路径一致）。 */
    private Object[] buildShiftCompare(List<Img> main, LocalTime dayS, LocalTime dayE) {
        Map<String, long[]> agg = new LinkedHashMap<>(); // type -> [dayCount, nightCount, total]
        for (Img img : main) {
            boolean day = inWindow(img.captureTime.toLocalTime(), dayS, dayE);
            for (ImageBoxRow b : img.boxes) {
                long[] v = agg.get(b.boxType);
                if (v == null) agg.put(b.boxType, new long[]{day ? 1 : 0, day ? 0 : 1, 1});
                else {
                    if (day) v[0]++;
                    else v[1]++;
                    v[2]++;
                }
            }
        }
        int totalTypes = agg.size();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("defectType", e.getKey());
            row.put("dayCount", e.getValue()[0]);
            row.put("nightCount", e.getValue()[1]);
            row.put("totalCount", e.getValue()[2]);
            out.add(row);
        }
        // ORDER BY totalCount DESC（并列按类型名定序）；★ 不设 LIMIT 12 —— 完整返回（R-2 终态）
        out.sort(Comparator.comparingLong((Map<String, Object> r) -> ((Number) r.get("totalCount")).longValue()).reversed()
                .thenComparing(r -> String.valueOf(r.get("defectType"))));
        boolean truncated = totalTypes > out.size();
        long truncatedCount = Math.max(totalTypes - out.size(), 0);
        return new Object[]{out, (long) totalTypes, truncated, truncatedCount};
    }

    // ==================== 小工具 ====================

    private static boolean inWindow(LocalTime t, LocalTime s, LocalTime e) {
        return t != null && s != null && e != null && t.compareTo(s) >= 0 && t.compareTo(e) < 0;
    }

    private static boolean hasBoxType(Img img, String type) {
        for (ImageBoxRow b : img.boxes) {
            if (eqCi(b.boxType, type)) return true;
        }
        return false;
    }

    /** CI 排序规则对齐：SQL 的 {@code =} 比较大小写不敏感。 */
    private static boolean eqCi(String a, String b) {
        if (a == null || b == null) return a == null && b == null;
        return a.equalsIgnoreCase(b);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /**
     * 与 Controller {@code resolveDimSplit} 同步的维度白名单（token 为 camelCase，
     * 不能 toLowerCase）；未识别的 dim 返回 null（与 resolveDimSplit 返回 null 的
     * 「无拆分」行为一致）。
     */
    private static String recognizedDim(String dim) {
        String t = trimToNull(dim);
        if (t == null) return null;
        switch (t) {
            case "camera":
            case "workstation":
            case "line":
            case "defectType":
            case "severity":
            case "shift":
                return t;
            default:
                return null;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static LocalTime parseHhmmss(Object v) {
        if (v == null) return null;
        String s = v.toString();
        if (s.length() == 5) s = s + ":00";
        return LocalTime.parse(s);
    }

    /** SQL：单值参数与集合参数取并集（lineId = lineIds[0] 兜底 + lineIds 全集）。 */
    private static Set<Long> idUnion(Long single, List<Long> multi) {
        Set<Long> set = new LinkedHashSet<>();
        if (single != null) set.add(single);
        if (multi != null) set.addAll(multi);
        return set;
    }

    private static long asLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
