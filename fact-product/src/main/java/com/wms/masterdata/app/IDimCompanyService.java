package com.wms.masterdata.app;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wms.masterdata.domain.model.DimCompany;
import java.util.List;

public interface IDimCompanyService extends IService<DimCompany> {
    DimCompany findByCompanyName(String companyName);
    DimCompany findByCompanyCode(String companyCode);
    boolean saveCompany(DimCompany company);
    boolean updateCompany(DimCompany company);
    boolean deleteCompany(Integer companyId);
}
