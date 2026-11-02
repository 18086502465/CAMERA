package com.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wms.entity.FactProductionInfo;
import com.wms.mapper.FactProductionInfoMapper;
import com.wms.service.IFactProductionInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
public class FactProductionInfoServiceImpl extends ServiceImpl<FactProductionInfoMapper, FactProductionInfo> implements IFactProductionInfoService {

    @Override
    public List<FactProductionInfo> listByDate(LocalDate date) {
        LambdaQueryWrapper<FactProductionInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FactProductionInfo::getProductionDate, date);
        return list(wrapper);
    }
}
