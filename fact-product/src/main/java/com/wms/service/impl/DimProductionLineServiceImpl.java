package com.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.DimProductionLine;
import com.wms.mapper.DimProductionLineMapper;
import com.wms.service.IDimProductionLineService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class DimProductionLineServiceImpl extends ServiceImpl<DimProductionLineMapper, DimProductionLine> implements IDimProductionLineService {

    @Override
    public List<DimProductionLine> listByFactoryId(Integer factoryId) {
        LambdaQueryWrapper<DimProductionLine> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DimProductionLine::getFactoryId, factoryId).eq(DimProductionLine::getIsDeleted, 0);
        return list(wrapper);
    }

    @Override
    public DimProductionLine findByLineName(String lineName) {
        LambdaQueryWrapper<DimProductionLine> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DimProductionLine::getLineName, lineName).eq(DimProductionLine::getIsDeleted, 0);
        return getOne(wrapper);
    }

    @Override
    public boolean saveLine(DimProductionLine line) {
        return save(line);
    }

    @Override
    public boolean updateLine(DimProductionLine line) {
        return updateById(line);
    }

    @Override
    public boolean deleteLine(Integer lineId) {
        DimProductionLine line = getById(lineId);
        if (line != null) {
            line.setIsDeleted(1);
            return updateById(line);
        }
        return false;
    }

    @Override
    public List<DimProductionLine> listByFactoryIds(List<Integer> factoryIds) {
        LambdaQueryWrapper<DimProductionLine> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(DimProductionLine::getFactoryId, factoryIds).eq(DimProductionLine::getIsDeleted, 0);
        return list(wrapper);
    }
}
