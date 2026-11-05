package com.wms.inspection.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wms.inspection.domain.model.FactProductionInfo;
import com.wms.inspection.infra.mapper.FactProductionInfoMapper;
import com.wms.inspection.app.IFactProductionInfoService;
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
