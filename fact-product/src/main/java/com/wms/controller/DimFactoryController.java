package com.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.entity.DimFactory;
import com.wms.service.IDimFactoryService;
import com.wms.vo.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 工厂管理 API
 */
@RestController
@RequestMapping("/api/factories")
public class DimFactoryController {

    @Autowired
    private IDimFactoryService factoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping
    public ResultVO<?> listAll(@RequestParam(required = false) Integer companyId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size,
                               @RequestParam(required = false) String keyword) {
        if (page != null || size != null) {
            int pageNo = page == null ? 1 : page;
            int pageSize = size == null ? 10 : size;
            LambdaQueryWrapper<DimFactory> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(companyId != null, DimFactory::getCompanyId, companyId);
            if (keyword != null && !keyword.trim().isEmpty()) {
                wrapper.and(w -> w.like(DimFactory::getFactoryName, keyword).or().like(DimFactory::getFactoryCode, keyword));
            }
            wrapper.orderByDesc(DimFactory::getUpdateTime).orderByDesc(DimFactory::getCreateTime);
            return ResultVO.success(factoryService.page(new Page<>(pageNo, pageSize), wrapper));
        }
        if (companyId != null) {
            return ResultVO.success(factoryService.listByCompanyId(companyId));
        }
        return ResultVO.success(factoryService.list());
    }

    @GetMapping("/{id}")
    public ResultVO<DimFactory> getById(@PathVariable Integer id) {
        return ResultVO.success(factoryService.getById(id));
    }

    @PostMapping
    public ResultVO<String> create(@RequestBody DimFactory factory) {
        if (factoryService.findByFactoryName(factory.getFactoryName()) != null) {
            return ResultVO.error("工厂名称已存在");
        }
        factoryService.saveFactory(factory);
        return ResultVO.success("创建成功");
    }

    @PutMapping("/{id}")
    public ResultVO<String> update(@PathVariable Integer id, @RequestBody DimFactory factory) {
        if (factoryService.getById(id) == null) {
            return ResultVO.fail("工厂不存在");
        }
        factory.setFactoryId(id);
        factoryService.updateFactory(factory);
        return ResultVO.success("更新成功");
    }

    /**
     * 删除工厂。
     *
     * <p><b>2026-11-04 修正</b>：原方法<b>缺少 {@code @PreAuthorize("hasRole('ADMIN')")}</b>，
     * 而同类的产线/工位/相机删除接口都有 —— 导致任何已登录用户都能删工厂。
     * 这是权限漏洞，本次补齐。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResultVO<String> delete(@PathVariable Integer id) {
        int lineCount = count("SELECT COUNT(*) FROM dim_production_line WHERE factory_id = ? AND IFNULL(is_deleted,0)=0", id);
        int userCount = count("SELECT COUNT(*) FROM sys_user WHERE factory_id = ?", id);
        if (lineCount > 0 || userCount > 0) {
            return ResultVO.fail("工厂存在依赖数据，禁止直接删除。请先迁移或删除下属产线/用户后再操作。",
                    "依赖明细：产线 " + lineCount + " 条，用户 " + userCount + " 条。");
        }
        jdbcTemplate.update("UPDATE dim_factory SET is_deleted = 1 WHERE factory_id = ?", id);
        return ResultVO.success("删除成功");
    }

    @GetMapping("/{id}/dependencies")
    public ResultVO<Map<String, Object>> dependencies(@PathVariable Integer id) {
        int lineCount = count("SELECT COUNT(*) FROM dim_production_line WHERE factory_id = ? AND IFNULL(is_deleted,0)=0", id);
        // ★ 2026-11-04 修正：dim_workstation 是逻辑删除，需过滤 is_deleted
        int stationCount = count("SELECT COUNT(*) FROM dim_workstation ws JOIN dim_production_line pl ON ws.line_id = pl.line_id WHERE pl.factory_id = ? AND IFNULL(pl.is_deleted,0)=0 AND IFNULL(ws.is_deleted,0)=0", id);
        // ★ 2026-11-04 修正：原查 camera_status（状态快照，可能无对应行），
        //   改为查 dim_camera（相机与产线绑定的真实来源）。
        int cameraCount = count("SELECT COUNT(*) FROM dim_camera dc JOIN dim_workstation ws ON dc.workstation_id = ws.workstation_id JOIN dim_production_line pl ON ws.line_id = pl.line_id WHERE pl.factory_id = ? AND IFNULL(dc.is_deleted,0)=0", id);
        int userCount = count("SELECT COUNT(*) FROM sys_user WHERE factory_id = ?", id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lineCount", lineCount);
        data.put("stationCount", stationCount);
        data.put("cameraCount", cameraCount);
        data.put("userCount", userCount);
        data.put("riskLevel", (lineCount + stationCount + cameraCount + userCount) > 0 ? "HIGH" : "LOW");
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
