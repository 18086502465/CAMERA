package com.wms.analytics;

import com.wms.analytics.infra.repository.AnomalyAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2 P0-1 告警落库去重的回归测试（2026-11-07）。
 *
 * <p>背景：{@code CameraAlertThrottle} 的去重是**进程内**状态，应用一重启就清零，
 * 下一个心跳重新 {@code shouldRaise()} → 又插一条新行，上一条永远没人关闭。现场
 * {@code fact_anomaly_alert} 里 78 条未恢复的 {@code TEMPERATURE_HIGH} 全指向同一
 * 工位同一类型，就是这么攒出来的；它们把 7 条 HIGH 级的 {@code CLEANUP_FAILED}
 * 挤出了前端 {@code LIMIT 20} 的窗口。
 *
 * <p>所以去重必须落在数据库侧。这里用 H2 跑真实 SQL，锁住四条语义：
 * 未恢复时重复上报只更新不新增、返回同一个 alert_id、已恢复后再上报要新建、
 * 不同类型/不同工位互不干扰。最后一条尤其重要 —— 把不同的告警错误合并，
 * 比重复告警更难发现。
 */
class AnomalyAlertDedupTest {

    private JdbcTemplate jdbc;
    private AnomalyAlertRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:alertdedup;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        ds.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(ds);
        repository = new AnomalyAlertRepository();
        // 生产用 @Autowired 注入；测试里直接赋字段，避免为了可测性改动生产代码结构
        org.springframework.test.util.ReflectionTestUtils.setField(repository, "jdbcTemplate", jdbc);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE fact_anomaly_alert (" +
                "alert_id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "line_id INT, workstation_id INT, detection_id BIGINT, " +
                "alert_type VARCHAR(64), alert_level VARCHAR(20), " +
                "alert_title VARCHAR(200), alert_message VARCHAR(500), message VARCHAR(500), " +
                "detect_time TIMESTAMP, is_resolved TINYINT, resolved_by VARCHAR(32), " +
                "resolved_at TIMESTAMP, is_active TINYINT DEFAULT 1, " +
                "create_time TIMESTAMP, update_time TIMESTAMP)");
    }

    private int rowCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM fact_anomaly_alert", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("同一工位同一类型未恢复时，重复上报只更新不新增，且返回同一个 alert_id")
    void repeatedRaiseWhileUnresolvedReusesRow() {
        Long first = repository.insertCameraAlert(1, 10, "TEMPERATURE_HIGH", "WARNING",
                "相机温度过高", "设备温度 76℃，超过阈值 70℃");
        assertNotNull(first);

        Long second = repository.insertCameraAlert(1, 10, "TEMPERATURE_HIGH", "WARNING",
                "相机温度过高", "设备温度 81℃，超过阈值 70℃");

        assertEquals(first, second, "复用未恢复行时必须回传同一个 alert_id，节流器要靠它做后续恢复");
        assertEquals(1, rowCount(), "未恢复期间重复上报不应产生新行");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT alert_message, message, alert_level FROM fact_anomaly_alert WHERE alert_id=?", first);
        assertEquals("设备温度 81℃，超过阈值 70℃", row.get("ALERT_MESSAGE"), "内容应刷新为最新一次上报");
        assertEquals("设备温度 81℃，超过阈值 70℃", row.get("MESSAGE"), "message 列是 NOT NULL 冗余列，必须同步");
    }

    @Test
    @DisplayName("告警已恢复后再次上报必须新建一行（不能把新一轮故障吞进旧行）")
    void raiseAfterResolveCreatesNewRow() {
        Long first = repository.insertCameraAlert(1, 10, "CAMERA_FAULT", "CRITICAL",
                "相机故障", "上报故障状态");
        assertEquals(1, repository.resolveCameraAlert(first, "AUTO_RECOVER"));

        Long second = repository.insertCameraAlert(1, 10, "CAMERA_FAULT", "CRITICAL",
                "相机故障", "再次上报故障状态");

        assertNotNull(second);
        assertTrue(second > first, "新故障应生成新的 alert_id");
        assertEquals(2, rowCount());
        Integer unresolved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fact_anomaly_alert WHERE is_resolved=0", Integer.class);
        assertEquals(1, unresolved, "同一时刻只应有一条未恢复");
    }

    @Test
    @DisplayName("不同类型 / 不同工位不得被合并")
    void differentTypeOrWorkstationKeepsSeparateRows() {
        Long temp = repository.insertCameraAlert(1, 10, "TEMPERATURE_HIGH", "WARNING", "相机温度过高", "76℃");
        Long cpu = repository.insertCameraAlert(1, 10, "CPU_HIGH", "WARNING", "相机CPU过高", "95%");
        Long otherStation = repository.insertCameraAlert(1, 11, "TEMPERATURE_HIGH", "WARNING", "相机温度过高", "76℃");

        assertEquals(3, rowCount(), "不同类型、不同工位是三个独立问题，必须各占一行");
        assertTrue(!temp.equals(cpu) && !cpu.equals(otherStation) && !temp.equals(otherStation));
    }

    @Test
    @DisplayName("line/workstation 传 null 时按 0 落库，且仍能去重（历史行为不能变）")
    void nullLineAndWorkstationCoercedToZeroAndDeduped() {
        Long first = repository.insertCameraAlert(null, null, "MEMORY_HIGH", "WARNING", "相机内存过高", "93%");
        Long second = repository.insertCameraAlert(null, null, "MEMORY_HIGH", "WARNING", "相机内存过高", "94%");

        assertEquals(first, second);
        assertEquals(1, rowCount());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT line_id, workstation_id FROM fact_anomaly_alert WHERE alert_id=?", first);
        assertEquals(0, ((Number) row.get("LINE_ID")).intValue());
        assertEquals(0, ((Number) row.get("WORKSTATION_ID")).intValue());
    }

    @Test
    @DisplayName("alert_type 为 null 时不走去重，直接插入（保持原行为，不做猜测性合并）")
    void nullAlertTypeAlwaysInserts() {
        Long first = repository.insertCameraAlert(1, 10, null, "WARNING", "未知告警", "x");
        Long second = repository.insertCameraAlert(1, 10, null, "WARNING", "未知告警", "y");
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(2, rowCount());
    }

    @Test
    @DisplayName("已恢复的历史行不参与去重：存在多条历史行时取最新一条未恢复行复用")
    void reusesLatestUnresolvedAmongDuplicates() {
        // 模拟现场存量脏数据：三条同键未恢复行
        for (String msg : new String[]{"第一次", "第二次", "第三次"}) {
            jdbc.update("INSERT INTO fact_anomaly_alert(line_id, workstation_id, alert_type, alert_level, " +
                            "alert_title, alert_message, message, detect_time, is_resolved, create_time, update_time) " +
                            "VALUES(1, 10, 'TEMPERATURE_HIGH', 'WARNING', '相机温度过高', ?, ?, NOW(), 0, NOW(), NOW())",
                    msg, msg);
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT alert_id FROM fact_anomaly_alert ORDER BY alert_id", Long.class);
        Long latest = ids.get(ids.size() - 1);

        Long reused = repository.insertCameraAlert(1, 10, "TEMPERATURE_HIGH", "WARNING",
                "相机温度过高", "第四次");

        assertEquals(latest, reused, "应复用最新那条未恢复行，不去动更早的历史脏数据");
        assertEquals(3, rowCount(), "存量重复行不在这里清理（心跳热路径上不做非幂等写）");
        Integer unresolved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fact_anomaly_alert WHERE is_resolved=0 AND alert_message='第四次'",
                Integer.class);
        assertEquals(1, unresolved);
    }

    @Test
    @DisplayName("resolveCameraAlert 只关闭目标行，不影响其他未恢复告警")
    void resolveOnlyAffectsTargetRow() {
        Long a = repository.insertCameraAlert(1, 10, "CPU_HIGH", "WARNING", "相机CPU过高", "95%");
        Long b = repository.insertCameraAlert(1, 11, "CPU_HIGH", "WARNING", "相机CPU过高", "96%");

        assertEquals(1, repository.resolveCameraAlert(a, "AUTO_RECOVER"));
        assertEquals(0, repository.resolveCameraAlert(a, "AUTO_RECOVER"), "重复关闭应返回 0（幂等）");
        assertNull(jdbc.queryForObject(
                "SELECT resolved_at FROM fact_anomaly_alert WHERE alert_id=?", java.sql.Timestamp.class, b),
                "另一条未恢复告警不应被连带关闭");
    }
}
