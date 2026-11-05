package com.wms.masterdata.api;

import com.wms.masterdata.domain.model.SysHolidayConfig;
import com.wms.masterdata.app.ISysHolidayConfigService;
import com.wms.shared.common.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

/**
 * 节假日配置 API
 */
@RestController
@RequestMapping("/api/holiday")
public class SysHolidayConfigController {

    @Autowired
    private ISysHolidayConfigService holidayConfigService;

    /**
     * 查询指定日期的配置
     */
    @GetMapping("/date")
    public ResultVO<SysHolidayConfig> getByDate(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") Date date) {
        return ResultVO.success(holidayConfigService.getByDate(date));
    }

    /**
     * 查询日期范围内的配置
     */
    @GetMapping("/range")
    public ResultVO<List<SysHolidayConfig>> getByRange(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") Date startDate,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") Date endDate) {
        return ResultVO.success(holidayConfigService.getByDateRange(startDate, endDate));
    }

    /**
     * 判断某日期是否为工作日
     */
    @GetMapping("/is-workday")
    public ResultVO<Boolean> isWorkday(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") Date date) {
        return ResultVO.success(holidayConfigService.isWorkday(date));
    }

    /**
     * 保存或更新配置
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResultVO<String> save(@RequestBody SysHolidayConfig config) {
        holidayConfigService.saveOrUpdateConfig(config);
        return ResultVO.success("保存成功");
    }

    /**
     * 批量保存（导入节假日配置）
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/batch")
    public ResultVO<String> saveBatch(@RequestBody List<SysHolidayConfig> configList) {
        for (SysHolidayConfig config : configList) {
            holidayConfigService.saveOrUpdateConfig(config);
        }
        return ResultVO.success("批量保存成功，共 " + configList.size() + " 条");
    }

    /**
     * 删除配置
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{configId}")
    public ResultVO<String> delete(@PathVariable Integer configId) {
        holidayConfigService.removeById(configId);
        return ResultVO.success("删除成功");
    }
}
