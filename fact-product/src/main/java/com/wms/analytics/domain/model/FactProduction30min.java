package com.wms.analytics.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 生产质量30分钟聚合事实表
 * DB列: id, line_id, workstation_id, brand_id, shift_id, bucket_date, bucket_index,
 *       start_time, end_time, total_count, ok_count, ng_count, yield_rate, create_time, update_time
 */
@TableName("fact_production_30min")
public class FactProduction30min implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("line_id")
    private Integer lineId;

    @TableField("workstation_id")
    private Integer workstationId;

    @TableField("brand_id")
    private Integer brandId;

    @TableField("shift_id")
    private Integer shiftId;

    @TableField("bucket_date")
    private LocalDate bucketDate;

    @TableField("bucket_index")
    private Integer bucketIndex;

    @TableField("start_time")
    private LocalTime startTime;

    @TableField("end_time")
    private LocalTime endTime;

    @TableField("total_count")
    private Integer totalCount;

    @TableField("ok_count")
    private Integer okCount;

    @TableField("ng_count")
    private Integer ngCount;

    @TableField("yield_rate")
    private BigDecimal yieldRate;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    // ===== Getters & Setters =====
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

    public LocalDate getBucketDate() { return bucketDate; }
    public void setBucketDate(LocalDate bucketDate) { this.bucketDate = bucketDate; }

    public Integer getBucketIndex() { return bucketIndex; }
    public void setBucketIndex(Integer bucketIndex) { this.bucketIndex = bucketIndex; }

    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }

    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }

    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }

    public Integer getOkCount() { return okCount; }
    public void setOkCount(Integer okCount) { this.okCount = okCount; }

    public Integer getNgCount() { return ngCount; }
    public void setNgCount(Integer ngCount) { this.ngCount = ngCount; }

    public BigDecimal getYieldRate() { return yieldRate; }
    public void setYieldRate(BigDecimal yieldRate) { this.yieldRate = yieldRate; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
