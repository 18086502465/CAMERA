package com.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.entity.DimWorkstation;
import com.wms.service.IDimWorkstationService;
import com.wms.vo.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/workstations")
public class DimWorkstationController {

    @Autowired
    private IDimWorkstationService workstationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping
    public ResultVO<?> listAll(@RequestParam(required = false) Integer lineId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size,
                               @RequestParam(required = false) String keyword) {
        if (page != null || size != null) {
            int pageNo = page == null ? 1 : page;
            int pageSize = size == null ? 10 : size;
            LambdaQueryWrapper<DimWorkstation> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(lineId != null, DimWorkstation::getLineId, lineId);
            if (keyword != null && !keyword.trim().isEmpty()) {
                wrapper.and(w -> w.like(DimWorkstation::getStationName, keyword).or().like(DimWorkstation::getStationCode, keyword));
            }
            wrapper.orderByAsc(DimWorkstation::getLineId).orderByAsc(DimWorkstation::getStationId);
            return ResultVO.success(workstationService.page(new Page<>(pageNo, pageSize), wrapper));
        }
        if (lineId != null) return ResultVO.success(workstationService.getByLineId(Long.valueOf(lineId)));
        return ResultVO.success(workstationService.list());
    }

    @GetMapping("/{id}")
    public ResultVO<DimWorkstation> getById(@PathVariable Integer id) {
        return ResultVO.success(workstationService.getById(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResultVO<String> create(@RequestBody DimWorkstation ws) {
        workstationService.save(ws);
        return ResultVO.success("创建成功");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    @Transactional
    public ResultVO<String> update(@PathVariable Integer id, @RequestBody DimWorkstation ws) {
        if (workstationService.getById(id) == null) {
            return ResultVO.fail("工位不存在");
        }
        ws.setStationId(id);
        workstationService.updateById(ws);
        if (ws.getLineId() != null) {
            jdbcTemplate.update("UPDATE camera_status SET line_id=?, update_time=NOW() WHERE workstation_id=?",
                    ws.getLineId(), id);
            jdbcTemplate.update(
                    "UPDATE dim_camera dc JOIN dim_production_line l ON l.line_id=? " +
                            "SET dc.line_id=?, dc.factory_id=l.factory_id, dc.workstation_id=? " +
                            "WHERE dc.workstation_id=? AND IFNULL(dc.is_deleted,0)=0",
                    ws.getLineId(), ws.getLineId(), id, id);
            jdbcTemplate.update(
                    "UPDATE cfg_camera_defect_mapping m JOIN dim_camera c ON m.camera_id=c.camera_id " +
                            "SET m.factory_id=c.factory_id, m.line_id=c.line_id, m.workstation_id=c.workstation_id, m.camera_code=c.camera_code " +
                            "WHERE c.workstation_id=? AND IFNULL(c.is_deleted,0)=0",
                    id);
        }
        return ResultVO.success("更新成功");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResultVO<String> delete(@PathVariable Integer id) {
        int cameraCount = count("SELECT COUNT(*) FROM camera_status WHERE workstation_id = ?", id);
        int productionCount = count("SELECT COUNT(*) FROM fact_production_30min WHERE workstation_id = ?", id);
        int defectCount = count("SELECT COUNT(*) FROM product_detection_history WHERE workstation_id = ?", id);
        if (cameraCount > 0) {
            return ResultVO.fail("工位已绑定相机，禁止直接删除。请先迁移或解绑相机后再操作。",
                    "依赖明细：相机 " + cameraCount + " 台，生产记录 " + productionCount + " 条，缺陷记录 " + defectCount + " 条。");
        }
        workstationService.removeById(id);
        return ResultVO.success("删除成功");
    }

    @GetMapping("/{id}/dependencies")
    public ResultVO<Map<String, Object>> dependencies(@PathVariable Integer id) {
        int cameraCount = count("SELECT COUNT(*) FROM camera_status WHERE workstation_id = ?", id);
        int productionCount = count("SELECT COUNT(*) FROM fact_production_30min WHERE workstation_id = ?", id);
        int defectCount = count("SELECT COUNT(*) FROM product_detection_history WHERE workstation_id = ?", id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cameraCount", cameraCount);
        data.put("productionCount", productionCount);
        data.put("defectCount", defectCount);
        data.put("riskLevel", (cameraCount + productionCount + defectCount) > 0 ? "HIGH" : "LOW");
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
