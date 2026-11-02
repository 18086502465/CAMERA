package com.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.entity.SysHolidayConfig;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

/**
 * 节假日配置 Mapper
 */
public interface SysHolidayConfigMapper extends BaseMapper<SysHolidayConfig> {

    /**
     * 根据日期查询配置
     */
    @Select("SELECT * FROM sys_holiday_config WHERE config_date = #{configDate}")
    SysHolidayConfig selectByDate(@Param("configDate") Date configDate);

    /**
     * 查询指定范围内的配置
     */
    @Select("SELECT * FROM sys_holiday_config WHERE config_date BETWEEN #{startDate} AND #{endDate} ORDER BY config_date")
    List<SysHolidayConfig> selectByDateRange(@Param("startDate") Date startDate, @Param("endDate") Date endDate);
}
