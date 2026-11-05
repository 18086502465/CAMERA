package com.wms.analytics.infra.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 异常告警仓储（DDD P3 批次③ 收敛）。
 *
 * <p>承载 {@code fact_anomaly_alert} 的直接写入。SQL 自
 * {@code CameraStatusServiceImpl.insertAnomalyAlert} <b>原样搬入</b>：
 * {@code detection_id} 固定写 NULL，{@code line_id} / {@code workstation_id}
 * 为空时写 0 —— 这两个"兜底为 0"的行为是原实现的一部分，<b>不可改成 NULL</b>。
 */
@Repository
public class AnomalyAlertRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 写入一条相机异常告警。
     *
     * @param lineId 产线 ID（为空按 0 写入）
     * @param workstationId 工位 ID（为空按 0 写入）
     */
    public int insertCameraAlert(Integer lineId, Integer workstationId, String alertType, String alertLevel,
                                  String alertTitle, String alertMessage) {
        return jdbcTemplate.update(
                "INSERT INTO fact_anomaly_alert(line_id, workstation_id, detection_id, alert_type, alert_level, alert_title, alert_message, is_resolved, create_time, update_time) " +
                        "VALUES(?, ?, NULL, ?, ?, ?, ?, 0, NOW(), NOW())",
                lineId == null ? 0 : lineId,
                workstationId == null ? 0 : workstationId,
                alertType,
                alertLevel,
                alertTitle,
                alertMessage);
    }
}
