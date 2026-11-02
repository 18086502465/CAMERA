package com.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wms.entity.SysHolidayConfig;

import java.util.Date;
import java.util.List;

/**
 * 节假日配置 Service 接口
 */
public interface ISysHolidayConfigService extends IService<SysHolidayConfig> {

    /**
     * 根据日期查询配置
     */
    SysHolidayConfig getByDate(Date date);

    /**
     * 查询指定范围内的配置
     */
    List<SysHolidayConfig> getByDateRange(Date startDate, Date endDate);

    /**
     * 判断某日期是否为工作日
     * @return true=工作日, false=休息日
     */
    boolean isWorkday(Date date);

    /**
     * 保存或更新配置
     */
    boolean saveOrUpdateConfig(SysHolidayConfig config);
}
