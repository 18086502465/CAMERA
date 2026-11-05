package com.wms.device.app.event;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 相机异常告警事件（DDD P4：device 域 → analytics 域的解耦事件）。
 *
 * <p>心跳/指标处理发现异常时由 device 域发布，analytics 域监听并落库
 * {@code fact_anomaly_alert}。发布方不关心消费方是否存在（解耦核心）。
 *
 * <p>载荷与原 {@code insertAnomalyAlert(...)} 参数一一对应，行为保持不变。
 */
public class CameraAlertEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Integer lineId;
    private final Integer workstationId;
    private final String alertType;
    private final String alertLevel;
    private final String alertTitle;
    private final String alertMessage;
    private final LocalDateTime occurredAt;

    public CameraAlertEvent(Integer lineId, Integer workstationId,
                            String alertType, String alertLevel,
                            String alertTitle, String alertMessage) {
        this.lineId = lineId;
        this.workstationId = workstationId;
        this.alertType = alertType;
        this.alertLevel = alertLevel;
        this.alertTitle = alertTitle;
        this.alertMessage = alertMessage;
        this.occurredAt = LocalDateTime.now();
    }

    public Integer getLineId() { return lineId; }

    public Integer getWorkstationId() { return workstationId; }

    public String getAlertType() { return alertType; }

    public String getAlertLevel() { return alertLevel; }

    public String getAlertTitle() { return alertTitle; }

    public String getAlertMessage() { return alertMessage; }

    public LocalDateTime getOccurredAt() { return occurredAt; }

    @Override
    public String toString() {
        return "CameraAlertEvent{type=" + alertType + ", level=" + alertLevel
                + ", line=" + lineId + ", ws=" + workstationId + "}";
    }
}
