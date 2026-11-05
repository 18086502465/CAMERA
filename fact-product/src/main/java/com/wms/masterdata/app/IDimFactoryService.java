package com.wms.masterdata.app;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wms.masterdata.domain.model.DimFactory;
import java.util.List;

public interface IDimFactoryService extends IService<DimFactory> {
    List<DimFactory> listByCompanyId(Integer companyId);
    DimFactory findByFactoryName(String factoryName);
    boolean saveFactory(DimFactory factory);
    boolean updateFactory(DimFactory factory);
    boolean deleteFactory(Integer factoryId);
}
