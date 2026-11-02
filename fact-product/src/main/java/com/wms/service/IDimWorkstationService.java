package com.wms.service;

import com.wms.entity.DimWorkstation;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 工位维度表 服务类
 * </p>
 *
 * @author 45344
 * @since 2026-01-15
 */
public interface IDimWorkstationService extends IService<DimWorkstation> {

    /**
     * 根据产线ID查询工位列表
     */
    List<DimWorkstation> getByLineId(Long lineId);
}
