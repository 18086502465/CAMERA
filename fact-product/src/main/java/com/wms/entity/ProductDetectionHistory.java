package com.wms.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 产品检测历史记录表（匹配实际DB列）
 */
@TableName("product_detection_history")
public class ProductDetectionHistory implements Serializable {

    private static final long serialVersionUID = 1L;

    // 主键映射：表 product_detection_history 的实际主键列是 `id`
    // （init_schema.sql 与 final_schema_p0_20260608.sql 均为 `id BIGINT AUTO_INCREMENT PRIMARY KEY`）。
    //
    // 【重要】@TableId **必须显式写 value**：
    // MyBatis-Plus 在 @TableId 未指定 value 时，会按**字段名驼峰转下划线**推导列名 ——
    // 字段名 detectionId 会推导出 `detection_id`，而表中并无该列。
    // 与 CameraImage 是同一类问题，同批修复。
    @TableId(value = "id", type = IdType.AUTO)
    private Long detectionId;

    private Integer lineId;
    private Integer workstationId;
    private Integer brandId;
    private Integer shiftId;

    @TableField("detection_time")
    private LocalDateTime detectionTime;

    private String captureSpeed;
    private String result;
    private String defectCode;
    private String defectArea;
    private String defectType;
    private String defectSize;
    private String imagePath;

    private BigDecimal confidence;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // ===== Getters & Setters =====
    public Long getDetectionId() { return detectionId; }
    public void setDetectionId(Long detectionId) { this.detectionId = detectionId; }

    // ===== 兼容旧代码的 getId/setId (@TableId 映射到表的 id 列) =====
    public Long getId() { return detectionId; }
    public void setId(Long id) { this.detectionId = id; }

    public Integer getLineId() { return lineId; }
    public void setLineId(Integer lineId) { this.lineId = lineId; }

    public Integer getWorkstationId() { return workstationId; }
    public void setWorkstationId(Integer workstationId) { this.workstationId = workstationId; }

    public Integer getBrandId() { return brandId; }
    public void setBrandId(Integer brandId) { this.brandId = brandId; }

    public Integer getShiftId() { return shiftId; }
    public void setShiftId(Integer shiftId) { this.shiftId = shiftId; }

    public LocalDateTime getDetectionTime() { return detectionTime; }
    public void setDetectionTime(LocalDateTime detectionTime) { this.detectionTime = detectionTime; }

    public String getCaptureSpeed() { return captureSpeed; }
    public void setCaptureSpeed(String captureSpeed) { this.captureSpeed = captureSpeed; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getDefectCode() { return defectCode; }
    public void setDefectCode(String defectCode) { this.defectCode = defectCode; }

    public String getDefectArea() { return defectArea; }
    public void setDefectArea(String defectArea) { this.defectArea = defectArea; }

    public String getDefectType() { return defectType; }
    public void setDefectType(String defectType) { this.defectType = defectType; }

    public String getDefectSize() { return defectSize; }
    public void setDefectSize(String defectSize) { this.defectSize = defectSize; }

    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }

    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
