package com.wms.controller;

import com.wms.entity.*;
import com.wms.service.*;
import com.wms.vo.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 级联数据 API — 一次性返回工厂→产线→工位→品牌→班次全量数据
 */
@RestController
@RequestMapping("/api/cascade")
public class CascadeController {

    @Autowired
    private IDimFactoryService factoryService;

    @Autowired
    private IDimProductionLineService lineService;

    @Autowired
    private IDimWorkstationService workstationService;

    @Autowired
    private IDimProductBrandService brandService;

    @Autowired
    private IDimShiftService shiftService;

    /**
     * 返回前端级联筛选所需的全量数据
     */
    @GetMapping("/all")
    public ResultVO<Map<String, Object>> getAll() {
        List<DimFactory> factories = factoryService.list();
        List<DimProductionLine> lines = lineService.list();
        List<DimWorkstation> workstations = workstationService.list();
        List<DimProductBrand> brands = brandService.list();
        List<DimShift> shifts = shiftService.list();

        // 构建树形级联结构：工厂 → 产线 → 工位
        List<Map<String, Object>> factoryTree = factories.stream().map(f -> {
            List<DimProductionLine> factoryLines = lines.stream()
                .filter(l -> l.getFactoryId().equals(f.getFactoryId()))
                .collect(Collectors.toList());

            List<Map<String, Object>> lineList = factoryLines.stream().map(l -> {
                List<DimWorkstation> lineStations = workstations.stream()
                    .filter(w -> w.getLineId().equals(l.getLineId()))
                    .collect(Collectors.toList());
                Map<String, Object> lineMap = new LinkedHashMap<>();
                lineMap.put("lineId", l.getLineId());
                lineMap.put("lineName", l.getLineName());
                lineMap.put("lineCode", l.getLineCode());
                lineMap.put("stations", lineStations.stream()
                    .map(this::buildStationMap)
                    .collect(Collectors.toList()));
                return lineMap;
            }).collect(Collectors.toList());

            Map<String, Object> factoryMap = new LinkedHashMap<>();
            factoryMap.put("factoryId", f.getFactoryId());
            factoryMap.put("factoryName", f.getFactoryName());
            factoryMap.put("factoryCode", f.getFactoryCode());
            factoryMap.put("lines", lineList);
            return factoryMap;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factories", factoryTree);

        // 扁平列表（供 el-select 下拉用）
        result.put("flattenedFactories", factories.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("factoryId", f.getFactoryId());
            m.put("factoryName", f.getFactoryName());
            m.put("factoryCode", f.getFactoryCode());
            return m;
        }).collect(Collectors.toList()));

        result.put("lines", lines.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineId", l.getLineId());
            m.put("lineName", l.getLineName());
            m.put("lineCode", l.getLineCode());
            m.put("factoryId", l.getFactoryId());
            return m;
        }).collect(Collectors.toList()));
        result.put("flattenedLines", result.get("lines"));
        result.put("workstations", workstations.stream()
            .map(this::buildStationMap)
            .collect(Collectors.toList()));
        result.put("flattenedWorkstations", result.get("workstations"));

        result.put("brands", brands.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("brandId", b.getBrandId());
            m.put("brandName", b.getBrandName());
            m.put("brandCode", b.getBrandCode());
            return m;
        }).collect(Collectors.toList()));

        result.put("shifts", shifts.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shiftId", s.getShiftId());
            m.put("shiftName", s.getShiftName());
            m.put("shiftCode", s.getShiftCode());
            m.put("startTime", s.getStartTime() != null ? s.getStartTime().toString() : null);
            m.put("endTime", s.getEndTime() != null ? s.getEndTime().toString() : null);
            return m;
        }).collect(Collectors.toList()));

        return ResultVO.success(result);
    }

    private Map<String, Object> buildStationMap(DimWorkstation w) {
        Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("stationId", w.getStationId());
        sm.put("workstationId", w.getStationId());
        sm.put("lineId", w.getLineId());
        sm.put("stationCode", w.getStationCode());
        sm.put("stationName", w.getStationName());
        sm.put("stationLabel", buildStationLabel(w.getStationCode(), w.getStationName()));
        return sm;
    }

    private String buildStationLabel(String code, String name) {
        boolean hasCode = code != null && !code.trim().isEmpty();
        boolean hasName = name != null && !name.trim().isEmpty();
        if (hasCode && hasName) {
            String c = code.trim();
            String n = name.trim();
            String lowerCode = c.toLowerCase(Locale.ROOT);
            String lowerName = n.toLowerCase(Locale.ROOT);
            if (lowerName.equals(lowerCode)
                    || lowerName.startsWith(lowerCode)
                    || lowerName.startsWith(lowerCode + "-")
                    || lowerName.startsWith(lowerCode + "_")
                    || lowerName.startsWith(lowerCode + "/")) {
                return n;
            }
            return c + " " + n;
        }
        if (hasName) return name.trim();
        if (hasCode) return code.trim();
        return "未命名工位";
    }
}
