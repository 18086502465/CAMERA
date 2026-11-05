package com.wms.masterdata.app;

import com.wms.masterdata.domain.model.DimProductBrand;
import com.wms.masterdata.infra.mapper.DimProductBrandMapper;
import com.wms.masterdata.app.IDimProductBrandService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 产品品牌维度表 服务实现类
 * </p>
 *
 * @author 45344
 * @since 2026-01-16
 */
@Service
public class DimProductBrandServiceImpl extends ServiceImpl<DimProductBrandMapper, DimProductBrand> implements IDimProductBrandService {

}
