package com.wms.analytics.infra.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

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
     * @return 新插入行的 {@code alert_id}；取不到主键时返回 {@code null}
     */
    public Long insertCameraAlert(Integer lineId, Integer workstationId, String alertType, String alertLevel,
                                  String alertTitle, String alertMessage) {
        // ★ 2026-11-05 修复（P4 事件化验证时发现）：原 INSERT 缺 detect_time 与 message
        //   两列 —— 它们是 NOT NULL 无默认值，导致告警从未成功落库（被 catch 掩盖，
        //   表长期 0 行）。补：detect_time=NOW()（检测/发现时间）、message=alert_message。
        //
        // ★ 2026-11-06（v3 H-4）：改为回传自增主键 —— 告警节流器需要它来标记
        //   「续报接续」与「自动恢复」，否则只能靠 message 文本匹配（不可接受）。
        //
        // ★ 2026-11-07（v2 P0-1）：落库前去重。原因：CameraAlertThrottle 是**进程内**
        //   状态，应用一重启就清零，下一个心跳重新 shouldRaise() → 又插一条新行，
        //   而上一条永远没人关。现场 78 条未恢复 TEMPERATURE_HIGH 全指向同一工位、
        //   同一类型，就是这么攒出来的（这也把 7 条 HIGH 级 CLEANUP_FAILED 挤出了
        //   前端 LIMIT 窗口）。
        //   命中同 (line, workstation, type) 的未恢复行时：只刷新内容与时间、返回原
        //   alert_id —— 节流器拿到后照常 bind，后续恢复事件会关掉它，语义不变。
        //
        //   不用唯一索引兜底的原因：现场已有 78 条重复数据，加唯一索引会直接建不上；
        //   且 line_id/workstation_id 允许为 NULL（历史行为写 0），唯一索引对 NULL 不去重。
        //   并发风险可控：同一相机同一类型的告警由单线程心跳串行产生。
        Integer lineIdKey = lineId == null ? 0 : lineId;
        Integer workstationIdKey = workstationId == null ? 0 : workstationId;
        if (alertType != null) {
            Long existing = findUnresolvedAlertId(lineIdKey, workstationIdKey, alertType);
            if (existing != null) {
                jdbcTemplate.update(
                        "UPDATE fact_anomaly_alert SET alert_level=?, alert_title=?, alert_message=?, message=?, " +
                                "detect_time=NOW(), update_time=NOW() WHERE alert_id=?",
                        alertLevel, alertTitle, alertMessage, alertMessage, existing);
                return existing;
            }
        }

        org.springframework.jdbc.support.KeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO fact_anomaly_alert(line_id, workstation_id, detection_id, alert_type, alert_level, alert_title, alert_message, message, detect_time, is_resolved, create_time, update_time) " +
                            "VALUES(?, ?, NULL, ?, ?, ?, ?, ?, NOW(), 0, NOW(), NOW())",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, lineIdKey);
            ps.setObject(2, workstationIdKey);
            ps.setString(3, alertType);
            ps.setString(4, alertLevel);
            ps.setString(5, alertTitle);
            ps.setString(6, alertMessage);
            ps.setString(7, alertMessage);   // message 列（NOT NULL）
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /**
     * 查同 (line, workstation, type) 的未恢复告警主键。
     *
     * <p>只取最新一条：历史脏数据可能有多条重复未恢复行（见 {@code insertCameraAlert} 注释），
     * 取最新那条继续使用，其余旧行保持原状 —— 不在这里顺手清理，避免在心跳热路径上做
     * 非幂等写。存量重复由运维一次性处理。
     */
    private Long findUnresolvedAlertId(Integer lineId, Integer workstationId, String alertType) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT alert_id FROM fact_anomaly_alert " +
                        "WHERE alert_type=? AND IFNULL(line_id,0)=? AND IFNULL(workstation_id,0)=? " +
                        "AND (is_resolved IS NULL OR is_resolved=0) " +
                        "ORDER BY alert_id DESC LIMIT 1",
                Long.class, alertType, lineId, workstationId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 关闭一条未恢复的告警（v3 H-4）。
     *
     * @param alertId    告警主键
     * @param resolvedBy 关闭者：{@code AUTO_RECOVER}=条件已恢复；{@code SUPERSEDED}=被续报接续
     * @return 影响行数（0 表示该行已被关闭或不存在，属正常竞态）
     */
    public int resolveCameraAlert(Long alertId, String resolvedBy) {
        if (alertId == null) {
            return 0;
        }
        return jdbcTemplate.update(
                "UPDATE fact_anomaly_alert SET is_resolved=1, resolved_by=?, resolved_at=NOW(), is_active=0, update_time=NOW() " +
                        "WHERE alert_id=? AND (is_resolved IS NULL OR is_resolved=0)",
                resolvedBy, alertId);
    }
}
