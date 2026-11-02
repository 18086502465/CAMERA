package com.wms.service;

import com.wms.entity.FactProductionInfo;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDate;
import java.util.List;

/**
 * <p>
 * 生产数据实时表 服务类
 * </p>
 *
 * @author 45344
 * @since 2026-01-10
 */
public interface IFactProductionInfoService extends IService<FactProductionInfo> {
    List<FactProductionInfo> listByDate(LocalDate date);
}
