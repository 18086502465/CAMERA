package com.wms.device.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("camera_status")
public class CameraStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("camera_name")
    private String cameraName;

    @TableField("camera_code")
    private String cameraCode;

    @TableField("line_id")
    private Integer lineId;

    @TableField("workstation_id")
    private Integer workstationId;

    @TableField("status")
    private String status;

    @TableField("cpu_usage")
    private BigDecimal cpuUsage;

    @TableField("memory_usage")
    private BigDecimal memoryUsage;

    @TableField("temperature")
    private BigDecimal temperature;

    @TableField("ok_count")
    private Integer okCount;

    @TableField("ng_count")
    private Integer ngCount;

    @TableField("total_count")
    private Integer totalCount;

    @TableField("yield_rate")
    private BigDecimal yieldRate;

    @TableField("latest_image_path")
    private String latestImagePath;

    @TableField("last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @TableField("last_upload_at")
    private LocalDateTime lastUploadAt;

    @TableField("product_name")
    private String productName;

    @TableField("report_time")
    private LocalDateTime reportTime;

    @TableField("recognize_count")
    private Integer recognizeCount;

    @TableField("correct_count")
    private Integer correctCount;

    @TableField("abnormal_count")
    private Integer abnormalCount;

    @TableField("error_message")
    private String errorMessage;

    @TableField("is_active")
    private Integer isActive;

    /**
     * 相机类型（2026-11-05 新增）。
     *
     * <p>REAL = 真实产线相机 → 数据<b>不参与</b>定时清理；
     * TEST = 测试相机 → 参与清理（无效图片 + 对应库记录）。
     *
     * <p>与 {@code dim_camera.camera_type} 保持一致（双表一致模式），
     * 其中 {@code dim_camera} 为权威源。
     */
    @TableField("camera_type")
    private String cameraType;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String stationName;

    @TableField(exist = false)
    private String stationCode;

    @TableField(exist = false)
    private String stationLabel;

    @TableField(exist = false)
    private Integer factoryId;

    @TableField(exist = false)
    private String lineName;

    @TableField(exist = false)
    private String lineCode;

    // ========== Getter/Setter ==========
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCameraName() { return cameraName; }
    public void setCameraName(String cameraName) { this.cameraName = cameraName; }

    public String getCameraCode() { return cameraCode; }
    public void setCameraCode(String cameraCode) { this.cameraCode = cameraCode; }

    public Integer getLineId() { return lineId; }
    public void setLineId(Integer lineId) { this.lineId = lineId; }

    public Integer getWorkstationId() { return workstationId; }
    public void setWorkstationId(Integer workstationId) { this.workstationId = workstationId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getCpuUsage() { return cpuUsage; }
    public void setCpuUsage(BigDecimal cpuUsage) { this.cpuUsage = cpuUsage; }

    public BigDecimal getMemoryUsage() { return memoryUsage; }
    public void setMemoryUsage(BigDecimal memoryUsage) { this.memoryUsage = memoryUsage; }

    public BigDecimal getTemperature() { return temperature; }
    public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }

    public Integer getOkCount() { return okCount; }
    public void setOkCount(Integer okCount) { this.okCount = okCount; }

    public Integer getNgCount() { return ngCount; }
    public void setNgCount(Integer ngCount) { this.ngCount = ngCount; }

    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }

    public BigDecimal getYieldRate() { return yieldRate; }
    public void setYieldRate(BigDecimal yieldRate) { this.yieldRate = yieldRate; }

    public String getLatestImagePath() { return latestImagePath; }
    public void setLatestImagePath(String latestImagePath) { this.latestImagePath = latestImagePath; }

    public LocalDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(LocalDateTime lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }

    public LocalDateTime getLastUploadAt() { return lastUploadAt; }
    public void setLastUploadAt(LocalDateTime lastUploadAt) { this.lastUploadAt = lastUploadAt; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public LocalDateTime getReportTime() { return reportTime; }
    public void setReportTime(LocalDateTime reportTime) { this.reportTime = reportTime; }

    public Integer getRecognizeCount() { return recognizeCount; }
    public void setRecognizeCount(Integer recognizeCount) { this.recognizeCount = recognizeCount; }

    public Integer getCorrectCount() { return correctCount; }
    public void setCorrectCount(Integer correctCount) { this.correctCount = correctCount; }

    public Integer getAbnormalCount() { return abnormalCount; }
    public void setAbnormalCount(Integer abnormalCount) { this.abnormalCount = abnormalCount; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getIsActive() { return isActive; }
    public void setIsActive(Integer isActive) { this.isActive = isActive; }

    public String getCameraType() { return cameraType; }
    public void setCameraType(String cameraType) { this.cameraType = cameraType; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    public String getStationName() { return stationName; }
    public void setStationName(String stationName) { this.stationName = stationName; }

    public String getStationCode() { return stationCode; }
    public void setStationCode(String stationCode) { this.stationCode = stationCode; }

    public String getStationLabel() { return stationLabel; }
    public void setStationLabel(String stationLabel) { this.stationLabel = stationLabel; }

    public Integer getFactoryId() { return factoryId; }
    public void setFactoryId(Integer factoryId) { this.factoryId = factoryId; }

    public String getLineName() { return lineName; }
    public void setLineName(String lineName) { this.lineName = lineName; }

    public String getLineCode() { return lineCode; }
    public void setLineCode(String lineCode) { this.lineCode = lineCode; }
}

