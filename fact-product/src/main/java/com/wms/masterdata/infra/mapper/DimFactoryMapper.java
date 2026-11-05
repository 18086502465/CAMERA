package com.wms.masterdata.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.masterdata.domain.model.DimFactory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DimFactoryMapper extends BaseMapper<DimFactory> {
}
