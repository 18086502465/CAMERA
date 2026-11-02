package com.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wms.entity.*;
import com.wms.repository.stats.StatsQueryRepository;
import com.wms.service.*;
import com.wms.utils.ShiftCalculator;
import com.wms.vo.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统计报表 API
 * v3.1: 新增 brandStats、defect-overview，支持 factoryId 过滤
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    @Autowired private IFactProduction30minService production30minService;
    @Autowired private IProductDetectionHistoryService detectionHistoryService;
    @Autowired private IDimWorkstationService workstationService;
    @Autowired private IDimProductBrandService brandService;
    @Autowired private IDimShiftService shiftService;
    @Autowired private ICameraStatusService cameraStatusService;
    @Autowired private IDimFactoryService factoryService;
    @Autowired private IDimProductionLineService lineService;
    @Autowired private IFactProductionInfoService productionInfoService;
    @Autowired private StatsQueryRepository statsQueryRepository;

    // ===== worker functions =====

    /** 从 defectSize 字符串推导严重程度 */
    private String deriveSeverity(String defectSize) {
        double val = parseDefectSizeValue(defectSize);
        if (val > 50) return "严重";
        if (val >= 20) return "重大";
        return "轻微";
    }

    private double parseDefectSizeValue(String defectSize) {
        if (defectSize == null || defectSize.trim().isEmpty()) return 0.0;
        String value = defectSize.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(value);
        List<Double> nums = new ArrayList<>();
        while (matcher.find()) {
            nums.add(Double.parseDouble(matcher.group(1)));
        }
        if (nums.isEmpty()) return 0.0;
        if ((value.contains("x") || value.contains("*") || value.contains("×")) && nums.size() >= 2) {
            return nums.get(0) * nums.get(1);
        }
        return nums.get(0);
    }

    private String severityCode(String severityText) {
        if ("严重".equals(severityText)) return "HIGH";
        if ("重大".equals(severityText)) return "MEDIUM";
        return "LOW";
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate) return (LocalDate) value;
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }

    // ==================== 现有接口 ====================

    @GetMapping("/workstation-trend")
    public ResultVO<Map<String, Object>> getWorkstationTrend(
            @RequestParam Long workstationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String shift) {

        List<FactProduction30min> dataList = production30minService
                .getByWorkstationAndDateRange(workstationId, startDate, endDate, shift);

        List<String> timeLabels = new ArrayList<>();
        List<Integer> totalCounts = new ArrayList<>();
        List<Double> yieldRates = new ArrayList<>();

        for (FactProduction30min data : dataList) {
            timeLabels.add(data.getBucketDate().toString() + " " + data.getBucketIndex());
            totalCounts.add(data.getTotalCount());
            double yieldRate = (data.getTotalCount() == 0) ? 0.0 :
                    (double) data.getOkCount() / data.getTotalCount();
            yieldRates.add(yieldRate);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("timeLabels", timeLabels);
        result.put("totalCounts", totalCounts);
        result.put("yieldRates", yieldRates);
        return ResultVO.success(result);
    }

    @GetMapping("/product-shift-yield")
    public ResultVO<Map<String, Object>> getProductShiftYield(
            @RequestParam(required = false) Long brandId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<Map<String, Object>> rows = statsQueryRepository.queryProductShiftYieldRows(brandId, startDate, endDate);

        Map<LocalDate, Double> dayYieldMap = new LinkedHashMap<>();
        Map<LocalDate, Double> nightYieldMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDate date = toLocalDate(row.get("bucket_date"));
            int shiftId = ((Number) row.get("shift_id")).intValue();
            int total = ((Number) row.get("total_count")).intValue();
            int ok = ((Number) row.get("ok_count")).intValue();
            double rate = total > 0 ? Math.round(ok * 10000.0 / total) / 100.0 : 0.0;
            if (shiftId == 1) dayYieldMap.put(date, rate);
            if (shiftId == 2) nightYieldMap.put(date, rate);
        }

        Set<LocalDate> allDates = new TreeSet<>(dayYieldMap.keySet());
        allDates.addAll(nightYieldMap.keySet());

        List<String> dateLabels = new ArrayList<>();
        List<Double> dayYieldRates = new ArrayList<>();
        List<Double> nightYieldRates = new ArrayList<>();
        for (LocalDate date : allDates) {
            dateLabels.add(date.toString());
            dayYieldRates.add(dayYieldMap.getOrDefault(date, 0.0));
            nightYieldRates.add(nightYieldMap.getOrDefault(date, 0.0));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("dateLabels", dateLabels);
        result.put("dayYieldRates", dayYieldRates);
        result.put("nightYieldRates", nightYieldRates);
        return ResultVO.success(result);
    }

    @GetMapping("/workstation-defect-trend")
    public ResultVO<Map<String, Object>> getWorkstationDefectTrend(
            @RequestParam Long workstationId,
            @RequestParam String defectCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<ProductDetectionHistory> defectList = detectionHistoryService
                .getByWorkstationAndDefectAndDateRange(workstationId, defectCode, startDate, endDate);

        Map<LocalDate, Long> defectCountMap = defectList.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getDetectionTime() != null ? p.getDetectionTime().toLocalDate() : LocalDate.now(),
                        Collectors.counting()));

        List<FactProduction30min> productionList = production30minService
                .getByWorkstationAndDateRange(workstationId, startDate, endDate, null);

        Map<LocalDate, Integer> totalCountMap = productionList.stream()
                .collect(Collectors.groupingBy(FactProduction30min::getBucketDate,
                        Collectors.summingInt(FactProduction30min::getTotalCount)));

        List<String> dateLabels = new ArrayList<>();
        List<Long> defectCounts = new ArrayList<>();
        List<Double> defectRatios = new ArrayList<>();

        List<LocalDate> sortedDates = new ArrayList<>(defectCountMap.keySet());
        Collections.sort(sortedDates);

        for (LocalDate date : sortedDates) {
            dateLabels.add(date.toString());
            long defectCount = defectCountMap.getOrDefault(date, 0L);
            int totalCount = totalCountMap.getOrDefault(date, 0);
            double ratio = totalCount > 0 ? (double) defectCount / totalCount * 100 : 0.0;
            defectCounts.add(defectCount);
            defectRatios.add(ratio);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("dateLabels", dateLabels);
        result.put("defectCounts", defectCounts);
        result.put("defectRatios", defectRatios);
        return ResultVO.success(result);
    }

    @GetMapping("/lines")
    public ResultVO<List<DimProductionLine>> getLines(@RequestParam(required = false) Integer factoryId) {
        if (factoryId != null) return ResultVO.success(lineService.listByFactoryId(factoryId));
        return ResultVO.success(lineService.list());
    }

    @GetMapping("/brands")
    public ResultVO<List<DimProductBrand>> getBrands() {
        return ResultVO.success(brandService.list());
    }

    @GetMapping("/workstations")
    public ResultVO<List<DimWorkstation>> getWorkstations(@RequestParam(required = false) Long lineId) {
        if (lineId != null) return ResultVO.success(workstationService.getByLineId(lineId));
        return ResultVO.success(workstationService.list());
    }

    // ==================== P0 新增/扩展 ====================

    /**
     * 总览看板 KPI 聚合 (v3.1 扩展: brandStats + factoryId 过滤)
     */
    @GetMapping("/dashboard-overview")
    public ResultVO<Map<String, Object>> getDashboardOverview(
            @RequestParam(required = false) Integer factoryId,
            @RequestParam(required = false) String date) {

        LocalDate queryDate = (date != null) ? LocalDate.parse(date) : LocalDate.now();

        // 1. 相机统计（按 factoryId 过滤）
        List<CameraStatus> cameras = cameraStatusService.listAll();
        if (factoryId != null) {
            List<DimWorkstation> stations = workstationService.list();
            List<DimProductionLine> lines = lineService.list();
            Set<Integer> factoryLineIds = lines.stream()
                    .filter(l -> factoryId.equals(l.getFactoryId()))
                    .map(DimProductionLine::getLineId)
                    .collect(Collectors.toSet());
            Set<Integer> factoryWsIds = stations.stream()
                    .filter(s -> factoryLineIds.contains(s.getLineId()))
                    .map(DimWorkstation::getStationId)
                    .collect(Collectors.toSet());
            cameras = cameras.stream()
                    .filter(c -> c.getWorkstationId() != null && factoryWsIds.contains(c.getWorkstationId()))
                    .collect(Collectors.toList());
        }
        int cameraTotal = cameras.size();
        long cameraRunning = cameras.stream()
                .filter(c -> "RUNNING".equalsIgnoreCase(c.getStatus()) || "ONLINE".equalsIgnoreCase(c.getStatus()))
                .count();
        double runningRate = cameraTotal > 0 ? (double) cameraRunning / cameraTotal * 100 : 0.0;

        // 2. 今日良率
        double todayYieldRate = 0.0;
        int todayTotal = 0, todayOk = 0, todayNg = 0;
        List<FactProductionInfo> todayList = null;
        try {
            todayList = productionInfoService.listByDate(queryDate);
            if (factoryId != null && todayList != null) {
                todayList = todayList.stream()
                        .filter(info -> factoryId.equals(info.getFactoryId()))
                        .collect(Collectors.toList());
            }
            if (todayList != null && !todayList.isEmpty()) {
                for (FactProductionInfo info : todayList) {
                    todayTotal += info.getTotalCount() != null ? info.getTotalCount() : 0;
                    todayOk += info.getOkCount() != null ? info.getOkCount() : 0;
                    todayNg += info.getNgCount() != null ? info.getNgCount() : 0;
                }
                todayYieldRate = todayTotal > 0 ? (double) todayOk / todayTotal * 100 : 0.0;
            }
        } catch (Exception e) {
            List<FactProduction30min> today30minList = production30minService
                    .getByWorkstationAndDateRange(null, queryDate, queryDate, null);
            if (today30minList != null) {
                for (FactProduction30min d : today30minList) {
                    todayTotal += d.getTotalCount();
                    todayOk += d.getOkCount();
                    todayNg += d.getNgCount();
                }
                todayYieldRate = todayTotal > 0 ? (double) todayOk / todayTotal * 100 : 0.0;
            }
        }

        // 3. 产线良率排行
        List<Map<String, Object>> lineRanking = new ArrayList<>();
        try {
            if (todayList == null) todayList = productionInfoService.listByDate(queryDate);
            if (factoryId != null && todayList != null) {
                todayList = todayList.stream()
                        .filter(info -> factoryId.equals(info.getFactoryId()))
                        .collect(Collectors.toList());
            }
            if (todayList != null && !todayList.isEmpty()) {
                Map<Integer, int[]> lineStats = new LinkedHashMap<>();
                for (FactProductionInfo info : todayList) {
                    if (info.getLineId() == null) continue;
                    int[] stats = lineStats.computeIfAbsent(info.getLineId(), k -> new int[2]);
                    stats[0] += info.getTotalCount() != null ? info.getTotalCount() : 0;
                    stats[1] += info.getOkCount() != null ? info.getOkCount() : 0;
                }
                for (Map.Entry<Integer, int[]> entry : lineStats.entrySet()) {
                    int[] stats = entry.getValue();
                    double yield = stats[0] > 0 ? (double) stats[1] / stats[0] * 100 : 0.0;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("lineId", entry.getKey());
                    item.put("yieldRate", Math.round(yield * 100.0) / 100.0);
                    item.put("totalCount", stats[0]);
                    lineRanking.add(item);
                }
                lineRanking.sort((a, b) -> Double.compare(
                        (Double) b.get("yieldRate"), (Double) a.get("yieldRate")));
            }
        } catch (Exception ignored) {}

        // 4. brandStats — v3.1 新增
        List<Map<String, Object>> brandStats = new ArrayList<>();
        try {
            Map<Integer, DimProductBrand> brandMap = brandService.list().stream()
                    .collect(Collectors.toMap(DimProductBrand::getBrandId, b -> b));
            if (todayList != null && !todayList.isEmpty()) {
                Map<Integer, int[]> brandAgg = new LinkedHashMap<>();
                for (FactProductionInfo info : todayList) {
                    if (info.getBrandId() == null) continue;
                    int[] agg = brandAgg.computeIfAbsent(info.getBrandId(), k -> new int[3]);
                    agg[0] += info.getTotalCount() != null ? info.getTotalCount() : 0;
                    agg[1] += info.getOkCount() != null ? info.getOkCount() : 0;
                    agg[2] += info.getNgCount() != null ? info.getNgCount() : 0;
                }
                for (Map.Entry<Integer, int[]> entry : brandAgg.entrySet()) {
                    int[] agg = entry.getValue();
                    DimProductBrand brand = brandMap.get(entry.getKey());
                    double yield = agg[0] > 0 ? (double) agg[1] / agg[0] * 100 : 0.0;
                    Map<String, Object> bs = new LinkedHashMap<>();
                    bs.put("brandId", entry.getKey());
                    bs.put("name", brand != null ? brand.getBrandName() : "未知品牌");
                    bs.put("ok", agg[1]);
                    bs.put("ng", agg[2]);
                    bs.put("total", agg[0]);
                    bs.put("yieldRate", Math.round(yield * 100.0) / 100.0);
                    brandStats.add(bs);
                }
            }
        } catch (Exception ignored) {}

        // If the daily fact table has not been generated yet, keep the dashboard useful by
        // falling back to the latest camera heartbeat counters. These counters are cumulative
        // device values, but they are better than returning an empty lower dashboard area.
        if ((todayTotal == 0 || lineRanking.isEmpty() || brandStats.isEmpty()) && cameras != null && !cameras.isEmpty()) {
            int cameraCounterTotal = 0;
            int cameraCounterOk = 0;
            int cameraCounterNg = 0;
            for (CameraStatus camera : cameras) {
                cameraCounterTotal += camera.getTotalCount() != null ? camera.getTotalCount() : 0;
                cameraCounterOk += camera.getOkCount() != null ? camera.getOkCount() : 0;
                cameraCounterNg += camera.getNgCount() != null ? camera.getNgCount() : 0;
            }
            if (todayTotal == 0 && cameraCounterTotal > 0) {
                todayTotal = cameraCounterTotal;
                todayOk = cameraCounterOk;
                todayNg = cameraCounterNg;
                todayYieldRate = cameraCounterTotal > 0 ? (double) cameraCounterOk / cameraCounterTotal * 100 : 0.0;
            }

            if (lineRanking.isEmpty()) {
                Map<Integer, String> lineNameMap = lineService.list().stream()
                        .filter(l -> l.getLineId() != null)
                        .collect(Collectors.toMap(DimProductionLine::getLineId, DimProductionLine::getLineName, (a, b) -> a));
                Map<Integer, int[]> lineAgg = new LinkedHashMap<>();
                for (CameraStatus camera : cameras) {
                    if (camera.getLineId() == null) continue;
                    int[] agg = lineAgg.computeIfAbsent(camera.getLineId(), k -> new int[2]);
                    agg[0] += camera.getTotalCount() != null ? camera.getTotalCount() : 0;
                    agg[1] += camera.getOkCount() != null ? camera.getOkCount() : 0;
                }
                for (Map.Entry<Integer, int[]> entry : lineAgg.entrySet()) {
                    int[] agg = entry.getValue();
                    if (agg[0] <= 0) continue;
                    double yield = (double) agg[1] / agg[0] * 100;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("lineId", entry.getKey());
                    item.put("lineName", lineNameMap.getOrDefault(entry.getKey(), "产线 " + entry.getKey()));
                    item.put("yieldRate", Math.round(yield * 100.0) / 100.0);
                    item.put("totalCount", agg[0]);
                    lineRanking.add(item);
                }
                lineRanking.sort((a, b) -> Double.compare(
                        ((Number) b.get("yieldRate")).doubleValue(), ((Number) a.get("yieldRate")).doubleValue()));
            }

            if (brandStats.isEmpty()) {
                Map<String, int[]> productAgg = new LinkedHashMap<>();
                for (CameraStatus camera : cameras) {
                    int total = camera.getTotalCount() != null ? camera.getTotalCount() : 0;
                    int ok = camera.getOkCount() != null ? camera.getOkCount() : 0;
                    int ng = camera.getNgCount() != null ? camera.getNgCount() : 0;
                    if (total <= 0 && ok <= 0 && ng <= 0) continue;
                    String productName = camera.getProductName();
                    if (productName == null || productName.trim().isEmpty()) {
                        productName = "未上报产品";
                    }
                    int[] agg = productAgg.computeIfAbsent(productName, k -> new int[3]);
                    agg[0] += total;
                    agg[1] += ok;
                    agg[2] += ng;
                }
                for (Map.Entry<String, int[]> entry : productAgg.entrySet()) {
                    int[] agg = entry.getValue();
                    if (agg[0] <= 0) continue;
                    double yield = (double) agg[1] / agg[0] * 100;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", entry.getKey());
                    item.put("ok", agg[1]);
                    item.put("ng", agg[2]);
                    item.put("total", agg[0]);
                    item.put("yieldRate", Math.round(yield * 100.0) / 100.0);
                    brandStats.add(item);
                }
                brandStats.sort((a, b) -> Integer.compare(
                        ((Number) b.get("total")).intValue(), ((Number) a.get("total")).intValue()));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cameraTotal", cameraTotal);
        result.put("cameraRunning", (int) cameraRunning);
        result.put("runningRate", Math.round(runningRate * 100.0) / 100.0);
        result.put("todayTotal", todayTotal);
        result.put("todayOk", todayOk);
        result.put("todayNg", todayNg);
        result.put("todayYieldRate", Math.round(todayYieldRate * 100.0) / 100.0);
        result.put("lineRanking", lineRanking.stream().limit(5).collect(Collectors.toList()));
        result.put("brandStats", brandStats);

        return ResultVO.success(result);
    }

    /**
     * 缺陷总览 (v3.1 新建)
     * 返回：KPI / 小时趋势 / 按类型 / 按工位 / Top 缺陷
     */
    @GetMapping("/defect-overview")
    public ResultVO<Map<String, Object>> getDefectOverview(
            @RequestParam(required = false) Integer factoryId,
            @RequestParam(required = false) Integer lineId,
            @RequestParam(required = false) Integer stationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();

        LambdaQueryWrapper<ProductDetectionHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(ProductDetectionHistory::getDetectionTime, start);
        wrapper.lt(ProductDetectionHistory::getDetectionTime, end);
        wrapper.eq(ProductDetectionHistory::getResult, "NG");
        wrapper.ne(ProductDetectionHistory::getCaptureSpeed, "LOW");
        if (lineId != null) wrapper.eq(ProductDetectionHistory::getLineId, lineId);
        if (stationId != null) wrapper.eq(ProductDetectionHistory::getWorkstationId, stationId);

        List<ProductDetectionHistory> allDefects = detectionHistoryService.list(wrapper);

        // KPI
        int totalDefects = allDefects.size();
        int severeCount = 0, majorCount = 0, minorCount = 0;
        for (ProductDetectionHistory d : allDefects) {
            String sev = deriveSeverity(d.getDefectSize());
            switch (sev) {
                case "严重": severeCount++; break;
                case "重大": majorCount++; break;
                default: minorCount++; break;
            }
        }
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("total", totalDefects);
        kpi.put("severe", severeCount);
        kpi.put("major", majorCount);
        kpi.put("minor", minorCount);

        // 小时趋势
        Map<Integer, int[]> hourMap = new LinkedHashMap<>();
        for (int h = 0; h < 24; h++) hourMap.put(h, new int[3]);
        for (ProductDetectionHistory d : allDefects) {
            if (d.getDetectionTime() == null) continue;
            int h = d.getDetectionTime().getHour();
            int[] arr = hourMap.computeIfAbsent(h, k -> new int[3]);
            String sev = deriveSeverity(d.getDefectSize());
            switch (sev) {
                case "严重": arr[0]++; break;
                case "重大": arr[1]++; break;
                default: arr[2]++; break;
            }
        }
        List<Map<String, Object>> hourlyTrend = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            int[] arr = hourMap.getOrDefault(h, new int[3]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hour", h);
            item.put("severe", arr[0]);
            item.put("major", arr[1]);
            item.put("minor", arr[2]);
            hourlyTrend.add(item);
        }

        // 按缺陷类型
        Map<String, Long> typeMap = allDefects.stream()
                .filter(d -> d.getDefectType() != null && !d.getDefectType().isEmpty())
                .collect(Collectors.groupingBy(ProductDetectionHistory::getDefectType, Collectors.counting()));
        List<Map<String, Object>> byType = typeMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("defectType", e.getKey());
                    m.put("count", e.getValue().intValue());
                    m.put("ratio", totalDefects > 0 ? Math.round(e.getValue() * 1000.0 / totalDefects) / 10.0 : 0.0);
                    return m;
                }).collect(Collectors.toList());

        // 按工位
        Map<Object, Long> stationMap = allDefects.stream()
                .filter(d -> d.getWorkstationId() != null)
                .collect(Collectors.groupingBy(ProductDetectionHistory::getWorkstationId, Collectors.counting()));
        List<Map<String, Object>> byStation = stationMap.entrySet().stream()
                .sorted(Map.Entry.<Object, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("stationId", e.getKey());
                    m.put("count", e.getValue().intValue());
                    return m;
                }).collect(Collectors.toList());

        // Top 10 缺陷
        List<ProductDetectionHistory> sorted = allDefects.stream()
                .sorted(Comparator.comparing(ProductDetectionHistory::getDetectionTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10).collect(Collectors.toList());
        List<Map<String, Object>> topDefects = new ArrayList<>();
        for (ProductDetectionHistory d : sorted) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cameraName", d.getDefectCode() != null ? d.getDefectCode() : "");
            item.put("stationId", d.getWorkstationId());
            item.put("defectType", d.getDefectType());
            String severity = deriveSeverity(d.getDefectSize());
            item.put("severity", severity);
            item.put("severityCode", severityCode(severity));
            item.put("defectArea", d.getDefectArea());
            item.put("defectSize", d.getDefectSize());
            item.put("detectionTime", d.getDetectionTime() != null ? d.getDetectionTime().toString() : "");
            topDefects.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kpi", kpi);
        result.put("hourlyTrend", hourlyTrend);
        result.put("byType", byType);
        result.put("byStation", byStation);
        result.put("topDefects", topDefects);

        return ResultVO.success(result);
    }

    /**
     * 缺陷位置热力图 (Phase 3 新建)
     * 返回 缺陷区域 × 面积档位 网格数据
     */
    @GetMapping("/defect-heatmap")
    public ResultVO<Map<String, Object>> getDefectHeatmap(
            @RequestParam(required = false) Integer factoryId,
            @RequestParam(required = false) Integer lineId,
            @RequestParam(required = false) Integer stationId,
            @RequestParam(required = false) Integer brandId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();

        LambdaQueryWrapper<ProductDetectionHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(ProductDetectionHistory::getDetectionTime, start);
        wrapper.lt(ProductDetectionHistory::getDetectionTime, end);
        wrapper.eq(ProductDetectionHistory::getResult, "NG");
        wrapper.ne(ProductDetectionHistory::getCaptureSpeed, "LOW"); // 低速数据过滤
        if (lineId != null) wrapper.eq(ProductDetectionHistory::getLineId, lineId);
        if (stationId != null) wrapper.eq(ProductDetectionHistory::getWorkstationId, stationId);
        if (brandId != null) wrapper.eq(ProductDetectionHistory::getBrandId, brandId);

        List<ProductDetectionHistory> defects = detectionHistoryService.list(wrapper);

        // 收集所有唯一区域和面积档
        Set<String> areaSet = new LinkedHashSet<>();
        Set<String> sizeSet = new LinkedHashSet<>();
        for (ProductDetectionHistory d : defects) {
            if (d.getDefectArea() != null && !d.getDefectArea().isEmpty())
                areaSet.add(d.getDefectArea());
            String sizeLevel = deriveSizeLevel(d.getDefectSize());
            sizeSet.add(sizeLevel);
        }
        List<String> areas = new ArrayList<>(areaSet);
        List<String> sizeLevels = new ArrayList<>(sizeSet);

        // 构建网格数据: Map<区域, Map<面积档, 计数>>
        Map<String, Map<String, Integer>> gridMap = new LinkedHashMap<>();
        for (String area : areas) {
            gridMap.put(area, new LinkedHashMap<>());
            for (String level : sizeLevels) {
                gridMap.get(area).put(level, 0);
            }
        }
        for (ProductDetectionHistory d : defects) {
            String area = d.getDefectArea();
            if (area == null || area.isEmpty()) continue;
            String level = deriveSizeLevel(d.getDefectSize());
            Map<String, Integer> row = gridMap.get(area);
            if (row != null) {
                row.put(level, row.getOrDefault(level, 0) + 1);
            }
        }

        // 转为前端需要的数组格式
        List<List<Integer>> gridData = new ArrayList<>();
        for (String area : areas) {
            List<Integer> row = new ArrayList<>();
            Map<String, Integer> counts = gridMap.get(area);
            for (String level : sizeLevels) {
                row.add(counts != null ? counts.getOrDefault(level, 0) : 0);
            }
            gridData.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("areas", areas);
        result.put("sizeLevels", sizeLevels);
        result.put("gridData", gridData);

        return ResultVO.success(result);
    }

    /** 从 defectSize 推导面积档位 */
    private String deriveSizeLevel(String defectSize) {
        double val = parseDefectSizeValue(defectSize);
        if (val > 50) return "大型";
        if (val >= 20) return "中型";
        return "小型";
    }

    /**
     * 缺陷多类型趋势 (Phase 3 新建)
     * 返回 30min 粒度各缺陷类型时序数据 + 总量趋势
     */
    @GetMapping("/defect-trend")
    public ResultVO<Map<String, Object>> getDefectTrend(
            @RequestParam(required = false) Integer factoryId,
            @RequestParam(required = false) Integer lineId,
            @RequestParam(required = false) Integer stationId,
            @RequestParam(required = false) Integer brandId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();

        LambdaQueryWrapper<ProductDetectionHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(ProductDetectionHistory::getDetectionTime, start);
        wrapper.lt(ProductDetectionHistory::getDetectionTime, end);
        wrapper.eq(ProductDetectionHistory::getResult, "NG");
        wrapper.ne(ProductDetectionHistory::getCaptureSpeed, "LOW"); // 低速数据过滤
        if (lineId != null) wrapper.eq(ProductDetectionHistory::getLineId, lineId);
        if (stationId != null) wrapper.eq(ProductDetectionHistory::getWorkstationId, stationId);
        if (brandId != null) wrapper.eq(ProductDetectionHistory::getBrandId, brandId);

        List<ProductDetectionHistory> defects = detectionHistoryService.list(wrapper);

        // 生成全部 30min 时间桶
        long days = endDate.toEpochDay() - startDate.toEpochDay() + 1;
        List<String> timeLabels = new ArrayList<>();
        for (long d = 0; d < days; d++) {
            for (int h = 0; h < 24; h++) {
                for (int m = 0; m < 60; m += 30) {
                    LocalDate date = startDate.plusDays(d);
                    timeLabels.add(String.format("%s %02d:%02d", date, h, m));
                }
            }
        }

        // 收集所有缺陷类型
        Set<String> typeSet = new LinkedHashSet<>();
        for (ProductDetectionHistory d : defects) {
            if (d.getDefectType() != null && !d.getDefectType().isEmpty())
                typeSet.add(d.getDefectType());
        }
        List<String> defectTypes = new ArrayList<>(typeSet);

        // 按 30min 桶 + 类型聚合
        // index -> {type -> count}
        List<Map<String, Integer>> bucketAgg = new ArrayList<>();
        for (int i = 0; i < timeLabels.size(); i++)
            bucketAgg.add(new LinkedHashMap<>());

        for (ProductDetectionHistory d : defects) {
            if (d.getDetectionTime() == null || d.getDefectType() == null) continue;
            int idx = getBucketIndex(d.getDetectionTime(), startDate);
            if (idx < 0 || idx >= timeLabels.size()) continue;
            Map<String, Integer> agg = bucketAgg.get(idx);
            agg.put(d.getDefectType(), agg.getOrDefault(d.getDefectType(), 0) + 1);
        }

        // 按类型生成 series
        List<Map<String, Object>> defectTypeSeries = new ArrayList<>();
        for (String type : defectTypes) {
            List<Integer> series = new ArrayList<>();
            for (int i = 0; i < timeLabels.size(); i++) {
                series.add(bucketAgg.get(i).getOrDefault(type, 0));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", type);
            item.put("data", series);
            defectTypeSeries.add(item);
        }

        // 总量趋势
        List<Integer> totalSeries = new ArrayList<>();
        for (int i = 0; i < timeLabels.size(); i++) {
            Map<String, Integer> agg = bucketAgg.get(i);
            totalSeries.add(agg.values().stream().mapToInt(Integer::intValue).sum());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timeLabels", timeLabels);
        result.put("defectTypes", defectTypes);
        result.put("defectTypeSeries", defectTypeSeries);
        result.put("totalSeries", totalSeries);

        return ResultVO.success(result);
    }

    /** 计算检测时间在 timeLabels 中的索引 */
    private int getBucketIndex(LocalDateTime dt, LocalDate startDate) {
        long dayOffset = dt.toLocalDate().toEpochDay() - startDate.toEpochDay();
        if (dayOffset < 0) return -1;
        int halfHourIdx = dt.getHour() * 2 + (dt.getMinute() >= 30 ? 1 : 0);
        return (int) dayOffset * 48 + halfHourIdx;
    }

    /**
     * 良率趋势 (Phase 3 新建)
     * 30min 粒度产量-良率双轴时序 + 阈值线
     */
    @GetMapping("/yield-trend")
    public ResultVO<Map<String, Object>> getYieldTrend(
            @RequestParam(required = false) Integer lineId,
            @RequestParam(required = false) Integer workstationId,
            @RequestParam(required = false) Integer brandId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<String> timeLabels = new ArrayList<>();
        List<Integer> totalCounts = new ArrayList<>();
        List<Double> yieldRates = new ArrayList<>();
        double thresholdLine = 97.0; // 产品设计要求 97%

        List<Map<String, Object>> rows = statsQueryRepository.queryYieldTrendRows(lineId, workstationId, brandId, startDate, endDate);
        for (Map<String, Object> row : rows) {
            LocalDate date = toLocalDate(row.get("bucket_date"));
            int bucketIndex = ((Number) row.get("bucket_index")).intValue();
            int total = ((Number) row.get("total_count")).intValue();
            int ok = ((Number) row.get("ok_count")).intValue();
            timeLabels.add(date + " " + bucketIndex);
            totalCounts.add(total);
            yieldRates.add(total > 0 ? Math.round(ok * 10000.0 / total) / 100.0 : 0.0);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timeLabels", timeLabels);
        result.put("totalCounts", totalCounts);
        result.put("yieldRates", yieldRates);
        result.put("thresholdLine", thresholdLine);

        return ResultVO.success(result);
    }

    /**
     * 工位良率排行（按良率降序）。
     */
    @GetMapping("/station-ranking")
    public ResultVO<List<Map<String, Object>>> getStationRanking(
            @RequestParam(required = false) Integer lineId,
            @RequestParam(required = false) Integer brandId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        Map<Integer, DimWorkstation> stationMap = workstationService.list().stream()
                .collect(Collectors.toMap(DimWorkstation::getStationId, w -> w));

        List<Map<String, Object>> ranking = new ArrayList<>();
        for (Map<String, Object> row : statsQueryRepository.queryStationRankingRows(lineId, brandId, startDate, endDate)) {
            Integer sid = ((Number) row.get("workstation_id")).intValue();
            int total = ((Number) row.get("total_count")).intValue();
            int ok = ((Number) row.get("ok_count")).intValue();
            int ng = ((Number) row.get("ng_count")).intValue();
            DimWorkstation ws = stationMap.get(sid);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stationId", sid);
            item.put("stationName", ws != null ? ws.getStationName() : null);
            item.put("stationLabel", ws != null ? ws.getStationName() : ("工位" + sid));
            item.put("totalCount", total);
            item.put("okCount", ok);
            item.put("ngCount", ng);
            item.put("yieldRate", total > 0 ? Math.round(ok * 10000.0 / total) / 100.0 : 0.0);
            ranking.add(item);
        }
        ranking.sort((a, b) -> Double.compare(
                (Double) b.get("yieldRate"), (Double) a.get("yieldRate")));

        return ResultVO.success(ranking);
    }

    /**
     * 品牌良率对比 (Phase 3 新建)
     * 各品牌产量/良率对比 + KPI
     */
    @GetMapping("/brand-compare")
    public ResultVO<Map<String, Object>> getBrandCompare(
            @RequestParam(required = false) Integer lineId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        Map<LocalDate, Map<Integer, int[]>> dailyBrandAgg = new LinkedHashMap<>();
        for (Map<String, Object> row : statsQueryRepository.queryBrandCompareRows(lineId, startDate, endDate)) {
            LocalDate date = toLocalDate(row.get("bucket_date"));
            Integer bid = ((Number) row.get("brand_id")).intValue();
            Map<Integer, int[]> brandMap = dailyBrandAgg.computeIfAbsent(date, k -> new LinkedHashMap<>());
            int[] agg = brandMap.computeIfAbsent(bid, k -> new int[3]); // [total, ok, ng]
            agg[0] += ((Number) row.get("total_count")).intValue();
            agg[1] += ((Number) row.get("ok_count")).intValue();
            agg[2] += ((Number) row.get("ng_count")).intValue();
        }

        // 品牌名称
        Map<Integer, String> brandNames = brandService.list().stream()
                .collect(Collectors.toMap(DimProductBrand::getBrandId, DimProductBrand::getBrandName));

        // 时间线
        List<LocalDate> dates = new ArrayList<>(dailyBrandAgg.keySet());
        Collections.sort(dates);
        List<String> dateLabels = dates.stream().map(LocalDate::toString).collect(Collectors.toList());

        // 按品牌生成 series
        Set<Integer> allBrands = new LinkedHashSet<>();
        for (Map<Integer, int[]> m : dailyBrandAgg.values()) allBrands.addAll(m.keySet());

        List<Map<String, Object>> brandSeries = new ArrayList<>();
        Map<String, Object> kpiSummary = new LinkedHashMap<>();

        for (Integer bid : allBrands) {
            String name = brandNames.getOrDefault(bid, "品牌" + bid);
            List<Double> yields = new ArrayList<>();
            List<Integer> totals = new ArrayList<>();
            int sumTotal = 0, sumOk = 0;
            for (LocalDate date : dates) {
                Map<Integer, int[]> brandMap = dailyBrandAgg.get(date);
                int[] agg = brandMap != null ? brandMap.get(bid) : null;
                if (agg != null) {
                    totals.add(agg[0]);
                    yields.add(agg[0] > 0 ? Math.round((double) agg[1] / agg[0] * 10000.0) / 100.0 : 0.0);
                    sumTotal += agg[0];
                    sumOk += agg[1];
                } else {
                    totals.add(0);
                    yields.add(0.0);
                }
            }
            double avgYield = sumTotal > 0 ? Math.round((double) sumOk / sumTotal * 10000.0) / 100.0 : 0.0;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("brandId", bid);
            item.put("brandName", name);
            item.put("totalCount", sumTotal);
            item.put("avgYieldRate", avgYield);
            item.put("yieldTrend", yields);
            item.put("totalTrend", totals);
            brandSeries.add(item);

            Map<String, Object> kpi = new LinkedHashMap<>();
            kpi.put("totalCount", sumTotal);
            kpi.put("yieldRate", avgYield);
            kpiSummary.put(String.valueOf(bid), kpi);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dateLabels", dateLabels);
        result.put("brandSeries", brandSeries);
        result.put("kpiSummary", kpiSummary);

        return ResultVO.success(result);
    }
}
