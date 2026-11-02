package com.wms.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;

/**
 * 工厂维度表
 */
@TableName("dim_factory")
public class DimFactory {
    @TableId(type = IdType.AUTO)
    private Integer factoryId;
    private Integer companyId;
    private String factoryName;
    private String factoryCode;
    private String address;
    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;
    private Date createTime;
    private Date updateTime;

    public Integer getFactoryId() { return factoryId; }
    public void setFactoryId(Integer factoryId) { this.factoryId = factoryId; }
    public Integer getCompanyId() { return companyId; }
    public void setCompanyId(Integer companyId) { this.companyId = companyId; }
    public String getFactoryName() { return factoryName; }
    public void setFactoryName(String factoryName) { this.factoryName = factoryName; }
    public String getFactoryCode() { return factoryCode; }
    public void setFactoryCode(String factoryCode) { this.factoryCode = factoryCode; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public Integer getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Integer isDeleted) { this.isDeleted = isDeleted; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
