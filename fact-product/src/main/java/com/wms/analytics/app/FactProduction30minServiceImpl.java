package com.wms.analytics.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.analytics.domain.model.FactProduction30min;
import com.wms.analytics.infra.mapper.FactProduction30minMapper;
import com.wms.analytics.app.IFactProduction30minService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class FactProduction30minServiceImpl extends ServiceImpl<FactProduction30minMapper, FactProduction30min> implements IFactProduction30minService {

    @Override
    public IPage<FactProduction30min> pageQuery(
            Integer page, Integer size, Long lineId, Long workstationId,
            Long brandId, Integer shiftId, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<FactProduction30min> wrapper = new LambdaQueryWrapper<>();
        if (lineId != null) wrapper.eq(FactProduction30min::getLineId, lineId);
        if (workstationId != null) wrapper.eq(FactProduction30min::getWorkstationId, workstationId);
        if (brandId != null) wrapper.eq(FactProduction30min::getBrandId, brandId);
        if (shiftId != null) wrapper.eq(FactProduction30min::getShiftId, shiftId);
        if (startDate != null) wrapper.ge(FactProduction30min::getBucketDate, startDate);
        if (endDate != null) wrapper.le(FactProduction30min::getBucketDate, endDate);
        wrapper.orderByAsc(FactProduction30min::getBucketDate)
                .orderByAsc(FactProduction30min::getBucketIndex);
        return page(new Page<>(page, size), wrapper);
    }

    @Override
    public List<FactProduction30min> getByWorkstationAndDateRange(
            Long workstationId, LocalDate startDate, LocalDate endDate, String shift) {
        LambdaQueryWrapper<FactProduction30min> wrapper = new LambdaQueryWrapper<>();
        if (workstationId != null) wrapper.eq(FactProduction30min::getWorkstationId, workstationId);
        if (startDate != null) wrapper.ge(FactProduction30min::getBucketDate, startDate);
        if (endDate != null) wrapper.le(FactProduction30min::getBucketDate, endDate);
        // shift filter: "day"=shift_id=1, "night"=shift_id=2
        if ("day".equals(shift)) wrapper.eq(FactProduction30min::getShiftId, 1);
        else if ("night".equals(shift)) wrapper.eq(FactProduction30min::getShiftId, 2);
        wrapper.orderByAsc(FactProduction30min::getBucketDate)
                .orderByAsc(FactProduction30min::getBucketIndex);
        return list(wrapper);
    }

    @Override
    public List<FactProduction30min> getByBrandAndShiftAndDateRange(
            Long brandId, Integer shiftId, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<FactProduction30min> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FactProduction30min::getBrandId, brandId);
        wrapper.eq(FactProduction30min::getShiftId, shiftId);
        if (startDate != null) wrapper.ge(FactProduction30min::getBucketDate, startDate);
        if (endDate != null) wrapper.le(FactProduction30min::getBucketDate, endDate);
        wrapper.orderByAsc(FactProduction30min::getBucketDate)
                .orderByAsc(FactProduction30min::getBucketIndex);
        return list(wrapper);
    }

    @Override
    public List<FactProduction30min> getByWorkstationAndDefectAndDateRange(
            Long workstationId, String defectCode, LocalDate startDate, LocalDate endDate) {
        // This requires joining with detection history or storing defect_code in fact table
        // For now, return empty - to be implemented with proper schema
        LambdaQueryWrapper<FactProduction30min> wrapper = new LambdaQueryWrapper<>();
        if (workstationId != null) wrapper.eq(FactProduction30min::getWorkstationId, workstationId);
        if (startDate != null) wrapper.ge(FactProduction30min::getBucketDate, startDate);
        if (endDate != null) wrapper.le(FactProduction30min::getBucketDate, endDate);
        wrapper.orderByAsc(FactProduction30min::getBucketDate)
                .orderByAsc(FactProduction30min::getBucketIndex);
        return list(wrapper);
    }

    @Override
    public List<FactProduction30min> selectDataOlderThanMonths(int months) {
        // Use direct JDBC or custom mapper method
        // Placeholder - needs XML or @Select annotation
        return getBaseMapper().selectDataOlderThanMonths(months);
    }

    @Override
    public int deleteDataOlderThanMonths(int months) {
        return getBaseMapper().deleteDataOlderThanMonths(months);
    }
}
