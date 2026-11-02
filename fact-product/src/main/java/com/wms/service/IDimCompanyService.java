package com.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wms.entity.DimCompany;
import java.util.List;

public interface IDimCompanyService extends IService<DimCompany> {
    DimCompany findByCompanyName(String companyName);
    DimCompany findByCompanyCode(String companyCode);
    boolean saveCompany(DimCompany company);
    boolean updateCompany(DimCompany company);
    boolean deleteCompany(Integer companyId);
}
