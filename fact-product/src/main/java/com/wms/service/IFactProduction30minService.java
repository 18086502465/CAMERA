package com.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wms.entity.FactProduction30min;

import java.time.LocalDate;
import java.util.List;

/**
 * 生产数据30分钟聚合表 服务类
 */
public interface IFactProduction30minService extends IService<FactProduction30min> {

    /**
     * 分页查询生产数据
     */
    com.baomidou.mybatisplus.core.metadata.IPage<FactProduction30min> pageQuery(
            Integer page, Integer size, Long lineId, Long workstationId,
            Long brandId, Integer shiftId, LocalDate startDate, LocalDate endDate);

    /**
     * 按工位和日期范围查询
     */
    List<FactProduction30min> getByWorkstationAndDateRange(
            Long workstationId, LocalDate startDate, LocalDate endDate, String shift);

    /**
     * 按品牌和班次查询
     */
    List<FactProduction30min> getByBrandAndShiftAndDateRange(
            Long brandId, Integer shiftId, LocalDate startDate, LocalDate endDate);

    /**
     * 按工位、缺陷编码、日期范围查询（用于缺陷趋势）
     */
    List<FactProduction30min> getByWorkstationAndDefectAndDateRange(
            Long workstationId, String defectCode, LocalDate startDate, LocalDate endDate);

    /**
     * 查询指定月数之前的数据（用于归档）
     */
    List<FactProduction30min> selectDataOlderThanMonths(int months);

    /**
     * 删除指定月数之前的数据（用于归档后清理）
     */
    int deleteDataOlderThanMonths(int months);
}
