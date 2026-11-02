package com.wms.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;

/**
 * 节假日配置表
 */
@TableName("sys_holiday_config")
public class SysHolidayConfig {
    @TableId(type = IdType.AUTO)
    private Integer configId;

    private Date configDate;        // 日期
    private Integer dayType;       // 0-工作日, 1-休息日
    private String description;     // 说明（如：劳动节、调休）

    private Date createTime;
    private Date updateTime;

    // Getters and Setters
    public Integer getConfigId() { return configId; }
    public void setConfigId(Integer configId) { this.configId = configId; }

    public Date getConfigDate() { return configDate; }
    public void setConfigDate(Date configDate) { this.configDate = configDate; }

    public Integer getDayType() { return dayType; }
    public void setDayType(Integer dayType) { this.dayType = dayType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
