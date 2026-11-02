package com.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wms.entity.DimProductionLine;
import java.util.List;

public interface IDimProductionLineService extends IService<DimProductionLine> {
    List<DimProductionLine> listByFactoryId(Integer factoryId);
    DimProductionLine findByLineName(String lineName);
    boolean saveLine(DimProductionLine line);
    boolean updateLine(DimProductionLine line);
    boolean deleteLine(Integer lineId);
    List<DimProductionLine> listByFactoryIds(List<Integer> factoryIds);
}
