package com.wms.analytics.infra.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AnomalyAlertRepository.class);

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
            ps.setString(3, truncate(alertType, 50));
            ps.setString(4, truncate(alertLevel, 20));
            ps.setString(5, truncate(alertTitle, 200));
            // ★ 2026-11-19 评审 P1-3（由 P1-1 端到端验证发现）：
            //   alert_message / message 两列都是 **varchar(500)**，
            //   正文一旦超长 → MySQL 抛 `ERROR 1406 Data too long` → **整条告警写不进去**。
            //   而调用方普遍把异常 catch 掉 → **告警静默丢失**，运维完全看不到。
            //   现场实测：新加的 SCHEMA_INDEX_MISSING 告警（正文 ~700 字）写库失败，
            //   只在日志里留下一条 WARN；现有最长告警已 428 字，离上限只有一步之遥。
            //   现在**统一在这里截断** —— 所有调用方都受益，不必各自记得截。
            String body = truncate(alertMessage, ALERT_MESSAGE_MAX_LEN);
            ps.setString(6, body);
            ps.setString(7, body);   // message 列（NOT NULL）
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /**
     * 告警正文的最大长度。
     *
     * <p>2026-11-19 起 {@code fact_anomaly_alert.alert_message / message} 由
     * varchar(500) 扩到 <b>varchar(2000)</b>（现场 ALTER 已执行，INPLACE 不重建表）。
     * 原来的 500 太紧 —— 现场实测新告警正文 ~700 字直接写库失败
     * （{@code ERROR 1406 Data too long}），而异常被 catch 掉 → <b>告警静默丢失</b>。
     * 现有最长告警已达 428 字，扩容前已到上限边缘。
     */
    private static final int ALERT_MESSAGE_MAX_LEN = 2000;

    /**
     * 幂等扩宽告警正文列（启动期调用）。
     *
     * <p>★ 2026-11-19 评审 P1-3：{@code alert_message / message} 原本是 varchar(500)，
     * 正文超长 → MySQL 抛 {@code ERROR 1406 Data too long} → <b>整条告警写不进去</b>，
     * 而调用方普遍 catch 掉异常 → <b>告警静默丢失</b>。
     * 现场已用 {@code ALTER TABLE ... ALGORITHM=INPLACE, LOCK=NONE} 扩到 varchar(2000)
     * （INPLACE 不重建表、不阻塞读写）；这里做<b>幂等自愈</b>，
     * 保证其它环境（新库 / 备份恢复出来的库）也能自动到位。
     *
     * <p>失败只记 WARN，不阻断启动 —— 列宽不够只是告警正文会被截断，不是致命问题。
     */
    public void ensureMessageColumnWidth() {
        try {
            List<String> current = jdbcTemplate.queryForList(
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS " +
                            " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fact_anomaly_alert' " +
                            "   AND COLUMN_NAME IN ('alert_message','message')",
                    String.class);
            if (current.isEmpty()) {
                return;
            }
            for (String col : current) {
                if ("alert_message".equals(col)) {
                    jdbcTemplate.execute("ALTER TABLE fact_anomaly_alert " +
                            "MODIFY COLUMN alert_message VARCHAR(2000) NULL COMMENT '告警正文'");
                } else {
                    jdbcTemplate.execute("ALTER TABLE fact_anomaly_alert " +
                            "MODIFY COLUMN message VARCHAR(2000) NOT NULL COMMENT '告警内容'");
                }
            }
            log.info("fact_anomaly_alert 告警正文列已扩到 varchar(2000)（评审 P1-3）");
        } catch (Exception e) {
            log.warn("扩宽 fact_anomaly_alert 正文列失败（不影响启动，告警正文会被截断到 500 字）: {}", e.getMessage());
        }
    }

    /**
     * 按列长度截断文本（超长时以省略号收尾）。
     *
     * <p>★ 2026-11-19 评审 P1-3：告警正文超长会让整条告警写不进库，
     * 而异常通常被调用方 catch 掉 → <b>告警静默丢失</b>。统一在这里截断。
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }

    /**
     * 查同 (line, workstation, type) 的未恢复告警主键。
     *
     * <p>只取最新一条：历史脏数据可能有多条重复未恢复行（见 {@code insertCameraAlert} 注释），
     * 取最新那条继续使用，其余旧行保持原状 —— 不在这里顺手清理，避免在心跳热路径上做
     * 非幂等写。存量重复由运维一次性处理。
     *
     * <p>2026-11-17 改 public：{@code DailyReportGenerationTask} 成功后需要按
     * {@code (0,0,DAILY_REPORT_FAILED)} 找到活跃告警行做「成功自动恢复」，
     * 与备份/清理告警同一套恢复纪律。
     */
    public Long findUnresolvedAlertId(Integer lineId, Integer workstationId, String alertType) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT alert_id FROM fact_anomaly_alert " +
                        "WHERE alert_type=? AND IFNULL(line_id,0)=? AND IFNULL(workstation_id,0)=? " +
                        "AND (is_resolved IS NULL OR is_resolved=0) " +
                        "ORDER BY alert_id DESC LIMIT 1",
                Long.class, alertType, lineId, workstationId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 统计「当日 + 近 7 日」五类相机告警的按天数量，供日报「异常全景」展示趋势。
     *
     * <p>★ 2026-11-21 评审 P0-4：日报原来只取了 temperature 一类告警、且只有当日一个数字，
     * 既看不到趋势也看不到类型分布。这里补一个只读聚合：
     * <ul>
     *   <li>只统计五类**相机业务告警**，显式排除运维类（BACKUP_FAILED / DISK_* 等），
     *       避免把「磁盘告警」混进「相机异常」误导产线；</li>
     *   <li>按 detect_time 的自然日分组，前端据此画 7 日趋势；</li>
     *   <li>lineId 为 null 或 0 表示全厂，否则按 line_id 过滤；未配置产线的告警
     *       （line_id=0）在筛选具体产线时**不计入**，口径与相机目录一致；</li>
     *   <li>只读、失败由调用方 catch（日报主流程不受影响）。</li>
     * </ul>
     *
     * @return 每行：alert_type / day / cnt，按 day 升序、cnt 降序
     */
    public List<Map<String, Object>> countCameraAlertsByDay(java.time.LocalDate start,
                                                             java.time.LocalDate endExclusive,
                                                             Integer lineId) {
        String types = "('CAMERA_FAULT','COUNT_MISMATCH','CPU_HIGH','MEMORY_HIGH','TEMPERATURE_HIGH')";
        StringBuilder sql = new StringBuilder()
                .append("SELECT alert_type AS alertType, DATE(detect_time) AS day, COUNT(1) AS cnt ")
                .append("FROM fact_anomaly_alert ")
                .append("WHERE alert_type IN ").append(types)
                .append("  AND detect_time >= ? AND detect_time < ? ");
        List<Object> params = new java.util.ArrayList<>();
        params.add(java.sql.Timestamp.valueOf(start.atStartOfDay()));
        params.add(java.sql.Timestamp.valueOf(endExclusive.atStartOfDay()));
        if (lineId != null && lineId > 0) {
            sql.append("  AND line_id = ? ");
            params.add(lineId);
        }
        sql.append("GROUP BY alert_type, DATE(detect_time) ORDER BY day ASC, cnt DESC");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
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
