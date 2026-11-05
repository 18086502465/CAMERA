package com.wms.system.domain.model;

import java.sql.Timestamp;

/**
 * 相机缺陷映射行（DDD P3 批次②）。
 *
 * <p>对应 {@code cfg_camera_defect_mapping} 表的 19 个可写列。
 * 以具名字段承载，避免 INSERT / UPDATE 两条 SQL 的位置参数顺序被写错。
 */
public class CameraDefectMappingRow {

    private Long factoryId;
    private Long lineId;
    private Long workstationId;
    private Long cameraId;
    private String cameraCode;
    private Long productId;
    private String productName;
    private String sourceCode;
    private Long defectTypeId;
    private String defectCode;
    private String defectName;
    private Long positionId;
    private String positionCode;
    private String positionName;
    private String severity;
    private Integer isNg;
    private Integer isEnabled;
    private Timestamp effectiveStart;
    private Timestamp effectiveEnd;

    public Long getFactoryId() { return factoryId; }
    public void setFactoryId(Long factoryId) { this.factoryId = factoryId; }

    public Long getLineId() { return lineId; }
    public void setLineId(Long lineId) { this.lineId = lineId; }

    public Long getWorkstationId() { return workstationId; }
    public void setWorkstationId(Long workstationId) { this.workstationId = workstationId; }

    public Long getCameraId() { return cameraId; }
    public void setCameraId(Long cameraId) { this.cameraId = cameraId; }

    public String getCameraCode() { return cameraCode; }
    public void setCameraCode(String cameraCode) { this.cameraCode = cameraCode; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }

    public Long getDefectTypeId() { return defectTypeId; }
    public void setDefectTypeId(Long defectTypeId) { this.defectTypeId = defectTypeId; }

    public String getDefectCode() { return defectCode; }
    public void setDefectCode(String defectCode) { this.defectCode = defectCode; }

    public String getDefectName() { return defectName; }
    public void setDefectName(String defectName) { this.defectName = defectName; }

    public Long getPositionId() { return positionId; }
    public void setPositionId(Long positionId) { this.positionId = positionId; }

    public String getPositionCode() { return positionCode; }
    public void setPositionCode(String positionCode) { this.positionCode = positionCode; }

    public String getPositionName() { return positionName; }
    public void setPositionName(String positionName) { this.positionName = positionName; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public Integer getIsNg() { return isNg; }
    public void setIsNg(Integer isNg) { this.isNg = isNg; }

    public Integer getIsEnabled() { return isEnabled; }
    public void setIsEnabled(Integer isEnabled) { this.isEnabled = isEnabled; }

    public Timestamp getEffectiveStart() { return effectiveStart; }
    public void setEffectiveStart(Timestamp effectiveStart) { this.effectiveStart = effectiveStart; }

    public Timestamp getEffectiveEnd() { return effectiveEnd; }
    public void setEffectiveEnd(Timestamp effectiveEnd) { this.effectiveEnd = effectiveEnd; }
}
