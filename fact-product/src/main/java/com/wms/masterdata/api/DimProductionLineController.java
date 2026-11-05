package com.wms.masterdata.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.masterdata.domain.model.DimProductionLine;
import com.wms.masterdata.app.IDimProductionLineService;
import com.wms.shared.common.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 产线管理 API
 */
@RestController
@RequestMapping("/api/lines")
public class DimProductionLineController {

    @Autowired
    private IDimProductionLineService lineService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping
    public ResultVO<?> listAll(@RequestParam(required = false) Integer factoryId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size,
                               @RequestParam(required = false) String keyword) {
        if (page != null || size != null) {
            int pageNo = page == null ? 1 : page;
            int pageSize = size == null ? 10 : size;
            LambdaQueryWrapper<DimProductionLine> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(factoryId != null, DimProductionLine::getFactoryId, factoryId);
            if (keyword != null && !keyword.trim().isEmpty()) {
                wrapper.and(w -> w.like(DimProductionLine::getLineName, keyword).or().like(DimProductionLine::getLineCode, keyword));
            }
            wrapper.orderByDesc(DimProductionLine::getUpdateTime).orderByDesc(DimProductionLine::getCreateTime);
            return ResultVO.success(lineService.page(new Page<>(pageNo, pageSize), wrapper));
        }
        if (factoryId != null) {
            return ResultVO.success(lineService.listByFactoryId(factoryId));
        }
        return ResultVO.success(lineService.list());
    }

    @GetMapping("/{id}")
    public ResultVO<DimProductionLine> getById(@PathVariable Integer id) {
        return ResultVO.success(lineService.getById(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResultVO<String> create(@RequestBody DimProductionLine line) {
        if (lineService.findByLineName(line.getLineName()) != null) {
            return ResultVO.error("产线名称已存在");
        }
        lineService.saveLine(line);
        return ResultVO.success("创建成功");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    @Transactional
    public ResultVO<String> update(@PathVariable Integer id, @RequestBody DimProductionLine line) {
        if (lineService.getById(id) == null) {
            return ResultVO.fail("产线不存在");
        }
        line.setLineId(id);
        lineService.updateLine(line);
        if (line.getFactoryId() != null) {
            jdbcTemplate.update("UPDATE dim_camera SET factory_id=? WHERE line_id=? AND IFNULL(is_deleted,0)=0",
                    line.getFactoryId(), id);
            jdbcTemplate.update(
                    "UPDATE cfg_camera_defect_mapping m JOIN dim_camera c ON m.camera_id=c.camera_id " +
                            "SET m.factory_id=c.factory_id, m.line_id=c.line_id, m.workstation_id=c.workstation_id, m.camera_code=c.camera_code " +
                            "WHERE c.line_id=? AND IFNULL(c.is_deleted,0)=0",
                    id);
        }
        return ResultVO.success("更新成功");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResultVO<String> delete(@PathVariable Integer id) {
        // ★ 2026-11-04 修正：dim_workstation 补了 @TableLogic 后是**逻辑删除**，
        //   但这里的依赖检查没过滤 is_deleted → 已删除的工位仍被算作依赖，
        //   导致「工位已删、产线却永远删不掉」。
        int stationCount = count("SELECT COUNT(*) FROM dim_workstation WHERE line_id = ? AND IFNULL(is_deleted,0)=0", id);
        // ★ 2026-11-04 修正：原查 camera_status（状态快照，可能无对应行），
        //   改为查 dim_camera（相机与产线绑定的真实来源）。
        int cameraCount = count("SELECT COUNT(*) FROM dim_camera WHERE line_id = ? AND IFNULL(is_deleted,0)=0", id);
        if (stationCount > 0 || cameraCount > 0) {
            return ResultVO.fail("产线存在依赖数据，禁止直接删除。请先迁移或删除下属工位/相机后再操作。",
                    "依赖明细：工位 " + stationCount + " 条，相机 " + cameraCount + " 台。");
        }
        jdbcTemplate.update("UPDATE dim_production_line SET is_deleted = 1 WHERE line_id = ?", id);
        return ResultVO.success("删除成功");
    }

    @GetMapping("/{id}/dependencies")
    public ResultVO<Map<String, Object>> dependencies(@PathVariable Integer id) {
        // ★ 2026-11-04 修正：dim_workstation 补了 @TableLogic 后是**逻辑删除**，
        //   但这里的依赖检查没过滤 is_deleted → 已删除的工位仍被算作依赖，
        //   导致「工位已删、产线却永远删不掉」。
        int stationCount = count("SELECT COUNT(*) FROM dim_workstation WHERE line_id = ? AND IFNULL(is_deleted,0)=0", id);
        int cameraCount = count("SELECT COUNT(*) FROM dim_camera WHERE line_id = ? AND IFNULL(is_deleted,0)=0", id);
        int productionCount = count("SELECT COUNT(*) FROM fact_production_30min WHERE line_id = ?", id);
        int defectCount = count("SELECT COUNT(*) FROM product_detection_history WHERE line_id = ?", id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stationCount", stationCount);
        data.put("cameraCount", cameraCount);
        data.put("productionCount", productionCount);
        data.put("defectCount", defectCount);
        data.put("riskLevel", (stationCount + cameraCount + productionCount + defectCount) > 0 ? "HIGH" : "LOW");
        return ResultVO.success(data);
    }

    private int count(String sql, Object... args) {
        try {
            Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
            return value == null ? 0 : value;
        } catch (Exception e) {
            return 0;
        }
    }
}
