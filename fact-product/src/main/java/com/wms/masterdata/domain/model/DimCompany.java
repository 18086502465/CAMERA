package com.wms.masterdata.domain.model;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;

/**
 * 公司维度表
 */
@TableName("dim_company")
public class DimCompany {
    @TableId(type = IdType.AUTO)
    private Integer companyId;
    private String companyName;
    private String companyCode;
    private String description;
    private String contactPerson;
    private String contactPhone;
    private String address;
    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;
    private Date createTime;
    private Date updateTime;

    // Getters and Setters
    public Integer getCompanyId() { return companyId; }
    public void setCompanyId(Integer companyId) { this.companyId = companyId; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getCompanyCode() { return companyCode; }
    public void setCompanyCode(String companyCode) { this.companyCode = companyCode; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getContactPerson() { return contactPerson; }
    public void setContactPerson(String contactPerson) { this.contactPerson = contactPerson; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public Integer getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Integer isDeleted) { this.isDeleted = isDeleted; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
