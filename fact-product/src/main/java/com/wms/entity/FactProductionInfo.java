package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 生产数据信息表
 * DB列: id, line_id, workstation_id, brand_id, shift_id, production_date,
 *       total_count, ok_count, ng_count, yield_rate, create_time, update_time
 */
@TableName("fact_production_info")
public class FactProductionInfo implements Serializable {

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

    @TableField("production_date")
    private LocalDate productionDate;

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
    public LocalDate getProductionDate() { return productionDate; }
    public void setProductionDate(LocalDate productionDate) { this.productionDate = productionDate; }
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

    // Compat: old code uses getFactoryId
    public Integer getFactoryId() { return null; }
    // Compat: old code uses getStartAt
    public LocalDateTime getStartAt() { return productionDate != null ? productionDate.atStartOfDay() : null; }
}
