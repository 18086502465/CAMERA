package com.wms.analytics.domain.model;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;

/**
 * 生产数据归档表
 */
@TableName("fact_production_30min_archive")
public class FactProduction30minArchive {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer lineId;
    private Integer workstationId;
    private Integer brandId;
    private Integer shiftId;
    private Date bucketDate;
    private Integer bucketIndex;
    private String startTime;
    private String endTime;
    private Integer totalCount;
    private Integer okCount;
    private Integer ngCount;
    private Double yieldRate;
    private Date archiveTime;
    private Date createTime;
    private Date updateTime;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getLineId() { return lineId; }
    public void setLineId(Integer lineId) { this.lineId = lineId; }

    public Integer getWorkstationId() { return workstationId; }
    public void setWorkstationId(Integer workstationId) { this.workstationId = workstationId; }

    public Integer getBrandId() { return brandId; }
    public void setBrandId(Integer brandId) { this.brandId = brandId; }

    public Integer getShiftId() { return shiftId; }
    public void setShiftId(Integer shiftId) { this.shiftId = shiftId; }

    public Date getBucketDate() { return bucketDate; }
    public void setBucketDate(Date bucketDate) { this.bucketDate = bucketDate; }

    public Integer getBucketIndex() { return bucketIndex; }
    public void setBucketIndex(Integer bucketIndex) { this.bucketIndex = bucketIndex; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }

    public Integer getOkCount() { return okCount; }
    public void setOkCount(Integer okCount) { this.okCount = okCount; }

    public Integer getNgCount() { return ngCount; }
    public void setNgCount(Integer ngCount) { this.ngCount = ngCount; }

    public Double getYieldRate() { return yieldRate; }
    public void setYieldRate(Double yieldRate) { this.yieldRate = yieldRate; }

    public Date getArchiveTime() { return archiveTime; }
    public void setArchiveTime(Date archiveTime) { this.archiveTime = archiveTime; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
