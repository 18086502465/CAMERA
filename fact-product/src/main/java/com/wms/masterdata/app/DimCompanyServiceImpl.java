package com.wms.masterdata.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.masterdata.domain.model.DimCompany;
import com.wms.masterdata.infra.mapper.DimCompanyMapper;
import com.wms.masterdata.app.IDimCompanyService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class DimCompanyServiceImpl extends ServiceImpl<DimCompanyMapper, DimCompany> implements IDimCompanyService {

    @Override
    public DimCompany findByCompanyName(String name) {
        LambdaQueryWrapper<DimCompany> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DimCompany::getCompanyName, name).eq(DimCompany::getIsDeleted, 0);
        return getOne(wrapper);
    }

    @Override
    public DimCompany findByCompanyCode(String code) {
        LambdaQueryWrapper<DimCompany> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DimCompany::getCompanyCode, code).eq(DimCompany::getIsDeleted, 0);
        return getOne(wrapper);
    }

    @Override
    public boolean saveCompany(DimCompany company) {
        return save(company);
    }

    @Override
    public boolean updateCompany(DimCompany company) {
        return updateById(company);
    }

    @Override
    public boolean deleteCompany(Integer companyId) {
        DimCompany company = getById(companyId);
        if (company != null) {
            company.setIsDeleted(1);
            return updateById(company);
        }
        return false;
    }
}
