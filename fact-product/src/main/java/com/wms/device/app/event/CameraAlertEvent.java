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
 *
 * <p><b>v3 H-4（2026-11-06）扩展</b>：
 * <ul>
 *   <li>{@link #alertId}：<b>由监听器回填</b>。监听器是同步 {@code @EventListener}，
 *       在 {@code publishEvent} 返回前完成落库，因此发布方可以安全读取 —— 这是
 *       告警节流器后续"续报置 SUPERSEDED / 恢复置已解决"能定位到具体行的前提；</li>
 *   <li>{@link #resolved}：为 {@code true} 时表示"恢复事件"，监听器不再插入新行，
 *       而是把 {@link #alertId} 对应的告警标记为已恢复。</li>
 * </ul>
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
    /** 恢复事件标记：true=标记已恢复，false=新建告警 */
    private final boolean resolved;
    /** 落库后回填的告警主键（同步监听器写入） */
    private Long alertId;

    public CameraAlertEvent(Integer lineId, Integer workstationId,
                            String alertType, String alertLevel,
                            String alertTitle, String alertMessage) {
        this(lineId, workstationId, alertType, alertLevel, alertTitle, alertMessage, false);
    }

    public CameraAlertEvent(Integer lineId, Integer workstationId,
                            String alertType, String alertLevel,
                            String alertTitle, String alertMessage,
                            boolean resolved) {
        this.lineId = lineId;
        this.workstationId = workstationId;
        this.alertType = alertType;
        this.alertLevel = alertLevel;
        this.alertTitle = alertTitle;
        this.alertMessage = alertMessage;
        this.resolved = resolved;
        this.occurredAt = LocalDateTime.now();
    }

    /**
     * 构造「告警关闭」事件（v3 H-4 续报接续 / 条件恢复）。
     *
     * <p>恢复事件不新建行，只带 {@code alertId} 让监听器执行 UPDATE，
     * 因此 {@code alertId} 由发布方给出（与新建事件相反 —— 新建时是监听器回填）。
     */
    public static CameraAlertEvent resolved(Integer lineId, Integer workstationId,
                                            String alertType, String alertTitle, Long alertId) {
        CameraAlertEvent event = new CameraAlertEvent(lineId, workstationId, alertType, "INFO",
                alertTitle + "（已恢复）", "告警已恢复：" + alertTitle, true);
        event.alertId = alertId;
        return event;
    }

    public Integer getLineId() { return lineId; }

    public Integer getWorkstationId() { return workstationId; }

    public String getAlertType() { return alertType; }

    public String getAlertLevel() { return alertLevel; }

    public String getAlertTitle() { return alertTitle; }

    public String getAlertMessage() { return alertMessage; }

    public LocalDateTime getOccurredAt() { return occurredAt; }

    public boolean isResolved() { return resolved; }

    public Long getAlertId() { return alertId; }

    /** 由监听器落库后回填；发布方不得主动设置 */
    public void setAlertId(Long alertId) { this.alertId = alertId; }

    @Override
    public String toString() {
        return "CameraAlertEvent{type=" + alertType + ", level=" + alertLevel
                + ", line=" + lineId + ", ws=" + workstationId
                + ", resolved=" + resolved + "}";
    }
}
