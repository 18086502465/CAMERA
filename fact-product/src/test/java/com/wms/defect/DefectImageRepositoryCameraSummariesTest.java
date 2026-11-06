package com.wms.defect;

import com.wms.defect.infra.repository.DefectImageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-01 / BUG-02 修复回归测试（2026-11-06）。
 *
 * <p>背景：{@code listRecentForSummaries} 的相机聚合查询原先把行级筛选
 * （keyword / severity / defectType / startTime / endTime）拼在外层
 * {@code WHERE r.rn <= ?} 之后并引用内层别名 {@code f}——窗口函数阻断派生表合并，
 * 任一筛选启用即 Error 1054（BUG-01）；且筛选后置导致「最新图不满足筛选则整台
 * 相机消失」（BUG-02 静默少图）。修复后筛选下推内层派生表，语义为
 * 「每相机满足筛选的最新一张，窗内无匹配图的相机不出现」。
 *
 * <p>用 H2 内存库（支持窗口函数）跑真实 SQL，防止这类问题以后只能在生产上暴露。
 */
class DefectImageRepositoryCameraSummariesTest {

    private JdbcTemplate jdbc;
    private DefectImageRepository repository;

    private static final LocalDateTime T_1101 = LocalDateTime.of(2026, 11, 1, 9, 0, 0);
    private static final LocalDateTime T_1105_08 = LocalDateTime.of(2026, 11, 5, 8, 0, 0);
    private static final LocalDateTime T_1105_12 = LocalDateTime.of(2026, 11, 5, 12, 0, 0);

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:camsummaries;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        ds.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(ds);
        repository = new DefectImageRepository(jdbc);
        createTables();
        seedData();
    }

    private void createTables() {
        // DB_CLOSE_DELAY=-1 使库在 JVM 内跨测试存活，先清空再建表
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE defect_image_file (" +
                "image_id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "batch_id BIGINT, camera_id VARCHAR(64), camera_name VARCHAR(128), " +
                "file_name VARCHAR(255), original_file_name VARCHAR(255), " +
                "image_path VARCHAR(512), image_url VARCHAR(512), " +
                "capture_time TIMESTAMP NULL, upload_time TIMESTAMP NULL, create_time TIMESTAMP NULL, " +
                "file_size BIGINT, width INT, height INT, " +
                "defect_count INT, high_count INT, medium_count INT, low_count INT)");
        jdbc.execute("CREATE TABLE defect_image_box (" +
                "box_id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "image_id BIGINT, defect_type VARCHAR(128), severity VARCHAR(32), " +
                "severity_level INT, x INT, y INT, width INT, height INT, area INT)");
        jdbc.execute("CREATE TABLE camera_status (" +
                "camera_code VARCHAR(64) PRIMARY KEY, workstation_id BIGINT, line_id BIGINT)");
        jdbc.execute("CREATE TABLE dim_workstation (" +
                "workstation_id BIGINT PRIMARY KEY, station_name VARCHAR(128), station_code VARCHAR(64))");
        jdbc.execute("CREATE TABLE dim_production_line (" +
                "line_id BIGINT PRIMARY KEY, line_name VARCHAR(128), line_code VARCHAR(64), factory_id BIGINT)");
    }

    private long insertImage(String cameraId, String fileName, LocalDateTime createTime,
                             String defectType, String severity) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("batch_id", 1L);
        args.put("camera_id", cameraId);
        args.put("camera_name", "CV1_Cam1_" + cameraId);
        args.put("file_name", fileName);
        args.put("original_file_name", fileName);
        args.put("capture_time", Timestamp.valueOf(createTime));
        args.put("create_time", Timestamp.valueOf(createTime));
        args.put("defect_count", defectType == null ? 0 : 1);
        if (severity != null && "HIGH".equalsIgnoreCase(severity)) {
            args.put("high_count", 1);
        }
        List<Object> names = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        args.forEach((k, v) -> { names.add(k); vals.add(v); });
        StringBuilder cols = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            cols.append(i == 0 ? "" : ",").append(names.get(i));
            marks.append(i == 0 ? "?" : ",?");
        }
        jdbc.update("INSERT INTO defect_image_file (" + cols + ") VALUES (" + marks + ")",
                vals.toArray());
        Long id = jdbc.queryForObject("SELECT MAX(image_id) FROM defect_image_file", Long.class);
        if (defectType != null) {
            jdbc.update("INSERT INTO defect_image_box (image_id, defect_type, severity) VALUES (?,?,?)",
                    id, defectType, severity);
        }
        return id;
    }

    private void seedData() {
        // CAM1：3 张，最新 11-05 12:00，最新一张带 HIGH 划痕
        insertImage("CAM1", "img1a.jpg", T_1101, null, null);
        insertImage("CAM1", "img1b.jpg", T_1105_08, "黑点", "LOW");
        insertImage("CAM1", "img1c.jpg", T_1105_12, "划痕", "HIGH");
        // CAM2：2 张，一张在 11-01（窗外），最新在窗内 11-05 08:00
        insertImage("CAM2", "img2a.jpg", T_1101, null, null);
        insertImage("CAM2", "img2b.jpg", T_1105_08, null, null);
        jdbc.update("INSERT INTO camera_status (camera_code, workstation_id, line_id) VALUES ('CAM1',1,1)");
        jdbc.update("INSERT INTO camera_status (camera_code, workstation_id, line_id) VALUES ('CAM2',1,1)");
        jdbc.update("INSERT INTO dim_workstation (workstation_id, station_name, station_code) VALUES (1,'工位1','WS01')");
        jdbc.update("INSERT INTO dim_production_line (line_id, line_name, line_code, factory_id) VALUES (1,'1号线','L01',10)");
    }

    private Map<String, List<Map<String, Object>>> query(String keyword, String severity, String defectType,
                                                         LocalDateTime start, LocalDateTime end) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (String cam : new String[]{"CAM1", "CAM2"}) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("cameraId", cam);
            s.put("cameraName", "CV1_Cam1_" + cam);
            summaries.add(s);
        }
        return repository.listRecentForSummaries(summaries, 1, null, null, null,
                keyword, severity, defectType, start, end, (code, name) -> name);
    }

    @Test
    @DisplayName("无筛选：每相机返回最新一张（rn=1）")
    void noFilterReturnsLatestPerCamera() {
        Map<String, List<Map<String, Object>>> r = query(null, null, null, null, null);
        assertEquals(2, r.size(), "两台相机都应出现");
        assertEquals("img1c.jpg", r.get("CAM1").get(0).get("fileName"));
        assertEquals("img2b.jpg", r.get("CAM2").get(0).get("fileName"));
    }

    @Test
    @DisplayName("时间窗：返回窗内最新一张，窗外相机不出现（BUG-01 不再 1054、BUG-02 语义正确）")
    void timeWindowFiltersPushedDown() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 5, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 6, 0, 0);
        Map<String, List<Map<String, Object>>> r = query(null, null, null, start, end);
        assertEquals(2, r.size());
        assertEquals("img1c.jpg", r.get("CAM1").get(0).get("fileName"));
        // CAM2 窗内最新是 img2b（11-05 08:00），而不是窗外更新的 11-01 那张导致整台消失
        assertEquals("img2b.jpg", r.get("CAM2").get(0).get("fileName"));
    }

    @Test
    @DisplayName("时间窗收窄到无匹配：相机不出现（下推语义验证）")
    void cameraWithoutMatchingImageIsAbsent() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 3, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 4, 0, 0);
        Map<String, List<Map<String, Object>>> r = query(null, null, null, start, end);
        assertFalse(r.containsKey("CAM1"), "CAM1 在 11-03~11-04 无图片，不应出现");
        assertFalse(r.containsKey("CAM2"));
    }

    @Test
    @DisplayName("keyword：命中 file_name 或 defect_type 两条路径")
    void keywordHitsFileNameAndDefectType() {
        // 命中缺陷类型（EXISTS 子查询路径）
        Map<String, List<Map<String, Object>>> byType = query("划痕", null, null, null, null);
        assertEquals(1, byType.size());
        assertEquals("CAM1", byType.keySet().iterator().next());
        assertEquals("img1c.jpg", byType.get("CAM1").get(0).get("fileName"));
        // 命中文件名（LIKE 路径）
        Map<String, List<Map<String, Object>>> byName = query("img2b", null, null, null, null);
        assertEquals(1, byName.size());
        assertEquals("CAM2", byName.keySet().iterator().next());
    }

    @Test
    @DisplayName("severity：EXISTS 路径命中 HIGH 框所在图片")
    void severityFilterHitsExistPath() {
        Map<String, List<Map<String, Object>>> r = query(null, "HIGH", null, null, null);
        assertEquals(1, r.size());
        assertEquals("CAM1", r.keySet().iterator().next());
        assertEquals("img1c.jpg", r.get("CAM1").get(0).get("fileName"));
        assertTrue(((Number) r.get("CAM1").get(0).get("highCount")).intValue() >= 1);
    }

    @Test
    @DisplayName("defectType：模糊匹配缺陷类型")
    void defectTypeFilterMatches() {
        Map<String, List<Map<String, Object>>> r = query(null, null, "黑点", null, null);
        assertEquals(1, r.size());
        assertEquals("CAM1", r.keySet().iterator().next());
        assertEquals("img1b.jpg", r.get("CAM1").get(0).get("fileName"));
    }
}
