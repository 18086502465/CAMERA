package com.wms.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;

/**
 * 产线维度表
 */
@TableName("dim_production_line")
public class DimProductionLine {
    @TableId(type = IdType.AUTO)
    private Integer lineId;
    private Integer factoryId;
    private String lineName;
    private String lineCode;
    private String description;
    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;
    private Date createTime;
    private Date updateTime;

    public Integer getLineId() { return lineId; }
    public void setLineId(Integer lineId) { this.lineId = lineId; }
    public Integer getFactoryId() { return factoryId; }
    public void setFactoryId(Integer factoryId) { this.factoryId = factoryId; }
    public String getLineName() { return lineName; }
    public void setLineName(String lineName) { this.lineName = lineName; }
    public String getLineCode() { return lineCode; }
    public void setLineCode(String lineCode) { this.lineCode = lineCode; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Integer isDeleted) { this.isDeleted = isDeleted; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
