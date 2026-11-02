package com.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.SysHolidayConfig;
import com.wms.mapper.SysHolidayConfigMapper;
import com.wms.service.ISysHolidayConfigService;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 节假日配置 Service 实现类
 */
@Service
public class SysHolidayConfigServiceImpl extends ServiceImpl<SysHolidayConfigMapper, SysHolidayConfig> implements ISysHolidayConfigService {

    @Override
    public SysHolidayConfig getByDate(Date date) {
        LambdaQueryWrapper<SysHolidayConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysHolidayConfig::getConfigDate, date);
        return getOne(wrapper);
    }

    @Override
    public List<SysHolidayConfig> getByDateRange(Date startDate, Date endDate) {
        LambdaQueryWrapper<SysHolidayConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(SysHolidayConfig::getConfigDate, startDate, endDate)
                .orderByAsc(SysHolidayConfig::getConfigDate);
        return list(wrapper);
    }

    @Override
    public boolean isWorkday(Date date) {
        SysHolidayConfig config = getByDate(date);
        if (config != null) {
            // dayType: 0=工作日, 1=休息日
            return config.getDayType() == 0;
        }

        // 默认：根据星期判断（周一到周五为工作日）
        java.time.DayOfWeek dow = date.toInstant()
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .getDayOfWeek();
        return dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY;
    }

    @Override
    public boolean saveOrUpdateConfig(SysHolidayConfig config) {
        // 先检查是否存在
        SysHolidayConfig existing = getByDate(config.getConfigDate());
        if (existing != null) {
            config.setConfigId(existing.getConfigId());
            return updateById(config);
        } else {
            return save(config);
        }
    }
}
