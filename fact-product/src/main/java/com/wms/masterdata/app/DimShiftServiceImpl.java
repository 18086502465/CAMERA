package com.wms.masterdata.app;

import com.wms.masterdata.domain.model.DimShift;
import com.wms.masterdata.infra.mapper.DimShiftMapper;
import com.wms.masterdata.app.IDimShiftService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 班次维度表 服务实现类
 * </p>
 *
 * @author 45344
 * @since 2026-01-15
 */
@Service
public class DimShiftServiceImpl extends ServiceImpl<DimShiftMapper, DimShift> implements IDimShiftService {

}
