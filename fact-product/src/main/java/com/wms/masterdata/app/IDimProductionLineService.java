package com.wms.masterdata.app;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wms.masterdata.domain.model.DimProductionLine;
import java.util.List;

public interface IDimProductionLineService extends IService<DimProductionLine> {
    List<DimProductionLine> listByFactoryId(Integer factoryId);
    DimProductionLine findByLineName(String lineName);
    boolean saveLine(DimProductionLine line);
    boolean updateLine(DimProductionLine line);
    boolean deleteLine(Integer lineId);
    List<DimProductionLine> listByFactoryIds(List<Integer> factoryIds);
}
