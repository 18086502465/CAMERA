package com.wms.service.impl;

import com.wms.entity.DimWorkstation;
import com.wms.mapper.DimWorkstationMapper;
import com.wms.service.IDimWorkstationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 工位维度表 服务实现类
 * </p>
 *
 * @author 45344
 * @since 2026-01-15
 */
@Service
public class DimWorkstationServiceImpl extends ServiceImpl<DimWorkstationMapper, DimWorkstation> implements IDimWorkstationService {

    @Override
    public List<DimWorkstation> getByLineId(Long lineId) {
        LambdaQueryWrapper<DimWorkstation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DimWorkstation::getLineId, lineId);
        return list(wrapper);
    }
}
