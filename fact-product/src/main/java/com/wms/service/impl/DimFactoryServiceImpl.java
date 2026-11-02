package com.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.DimFactory;
import com.wms.mapper.DimFactoryMapper;
import com.wms.service.IDimFactoryService;
import org.springframework.stereotype.Service;
import java.util.Date;
import java.util.List;

@Service
public class DimFactoryServiceImpl extends ServiceImpl<DimFactoryMapper, DimFactory> implements IDimFactoryService {

    @Override
    public List<DimFactory> listByCompanyId(Integer companyId) {
        LambdaQueryWrapper<DimFactory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DimFactory::getCompanyId, companyId).eq(DimFactory::getIsDeleted, 0);
        return list(wrapper);
    }

    @Override
    public DimFactory findByFactoryName(String factoryName) {
        LambdaQueryWrapper<DimFactory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DimFactory::getFactoryName, factoryName).eq(DimFactory::getIsDeleted, 0);
        return getOne(wrapper);
    }

    @Override
    public boolean saveFactory(DimFactory factory) {
        if (factory.getCompanyId() == null) {
            factory.setCompanyId(1);
        }
        if (factory.getIsDeleted() == null) {
            factory.setIsDeleted(0);
        }
        Date now = new Date();
        if (factory.getCreateTime() == null) {
            factory.setCreateTime(now);
        }
        if (factory.getUpdateTime() == null) {
            factory.setUpdateTime(now);
        }
        return save(factory);
    }

    @Override
    public boolean updateFactory(DimFactory factory) {
        return updateById(factory);
    }

    @Override
    public boolean deleteFactory(Integer factoryId) {
        DimFactory factory = getById(factoryId);
        if (factory != null) {
            factory.setIsDeleted(1);
            return updateById(factory);
        }
        return false;
    }
}
