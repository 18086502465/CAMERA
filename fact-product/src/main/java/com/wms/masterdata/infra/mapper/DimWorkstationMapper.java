package com.wms.masterdata.infra.mapper;

import com.wms.masterdata.domain.model.DimWorkstation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 工位维度表 Mapper 接口
 * </p>
 *
 * @author 45344
 * @since 2026-01-15
 */
@Mapper
public interface DimWorkstationMapper extends BaseMapper<DimWorkstation> {

}
