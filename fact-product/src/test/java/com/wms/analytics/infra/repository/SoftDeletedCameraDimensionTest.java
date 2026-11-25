package com.wms.analytics.infra.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 软删相机不进统计维度的回归测试（2026-11-24 深度评审 H3）。
 *
 * <p>背景：统计主路径（SQL 路径 joinCam / fast 路径 fetchCameraDims /
 * 聚合兜底 queryCameraRanking）的 dim_camera JOIN 原先<b>不过滤 is_deleted</b> ——
 * 软删相机仍提供产线/工站维度，历史图片被计入维度行，与图片列表路径
 * {@code cs.is_active=1}、日报路径 {@code REAL_CAMERA_JOIN} 的口径分裂。
 *
 * <p>本测试用 H2（MySQL 模式）真表冻结：
 * <ul>
 *   <li>fast 路径：软删相机的 camera_code 不得出现在维度 map；</li>
 *   <li>聚合兜底路径：软删相机的维度字段为 null（LEFT JOIN ON 条件语义），
 *       不得以其名称出现在排行中。</li>
 * </ul>
 */
class SoftDeletedCameraDimensionTest {

    private JdbcTemplate jdbc;
    private LineAnalyticsQueryRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:softdelcam;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false");
        ds.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(ds);
        repository = new LineAnalyticsQueryRepository(jdbc);
        createTables();
        seedData();
    }

    private void createTables() {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE dim_camera (" +
                "camera_id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "camera_code VARCHAR(50) NOT NULL, " +
                "camera_name VARCHAR(100) NOT NULL, " +
                "factory_id INT, line_id INT, workstation_id INT, " +
                "is_deleted TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE agg_quality_30m (" +
                "camera_id BIGINT, total_count INT, ok_count INT, ng_count INT)");
    }

    private void seedData() {
        jdbc.update("INSERT INTO dim_camera(camera_code, camera_name, factory_id, line_id, workstation_id, is_deleted) " +
                "VALUES ('CAM_LIVE', '在役相机', 1, 10, 100, 0)");
        jdbc.update("INSERT INTO dim_camera(camera_code, camera_name, factory_id, line_id, workstation_id, is_deleted) " +
                "VALUES ('CAM_DEAD', '已软删相机', 1, 11, 101, 1)");
        jdbc.update("INSERT INTO agg_quality_30m(camera_id, total_count, ok_count, ng_count) VALUES (1, 10, 8, 2)");
        jdbc.update("INSERT INTO agg_quality_30m(camera_id, total_count, ok_count, ng_count) VALUES (2, 50, 30, 20)");
    }

    @Test
    @DisplayName("fast 路径：fetchCameraDims 不返回软删相机的维度行")
    void fetchCameraDimsExcludesSoftDeleted() {
        Map<String, LineAnalyticsQueryRepository.CameraDimRow> dims = repository.fetchCameraDims();
        assertTrue(dims.containsKey("CAM_LIVE"), "在役相机应在维度 map 中");
        assertFalse(dims.containsKey("CAM_DEAD"), "软删相机不得出现在维度 map 中");
    }

    @Test
    @DisplayName("SQL 路径与聚合兜底路径的 JOIN 必须带 is_deleted 条件（源码契约，防回退）")
    void joinMustFilterSoftDeleted() throws Exception {
        // joinCam 是 Controller 内的局部变量、聚合兜底的 ON 条件在 Repository 源码里，
        // 均无法反射读取 —— 用源码文本断言冻结（改动这些字符串时本测试必须同步更新）。
        java.nio.file.Path ctrlSrc = java.nio.file.Paths.get(
                "src", "main", "java", "com", "wms", "analytics", "api", "LineAnalyticsController.java");
        String controller = new String(java.nio.file.Files.readAllBytes(ctrlSrc), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(controller.contains(
                        "LEFT JOIN dim_camera c ON c.camera_code = f.camera_id AND IFNULL(c.is_deleted, 0) = 0"),
                "统计主路径 joinCam 必须过滤软删相机（H3），改动时请同步本断言");

        java.nio.file.Path repoSrc = java.nio.file.Paths.get(
                "src", "main", "java", "com", "wms", "analytics", "infra", "repository", "LineAnalyticsQueryRepository.java");
        String repo = new String(java.nio.file.Files.readAllBytes(repoSrc), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(repo.contains("LEFT JOIN dim_camera c ON q.camera_id = c.camera_id AND IFNULL(c.is_deleted, 0) = 0"),
                "聚合兜底通道 queryCameraRanking 的 JOIN 必须过滤软删相机（H3）");
        assertTrue(repo.contains("FROM dim_camera WHERE IFNULL(is_deleted, 0) = 0"),
                "fast 路径 fetchCameraDims 必须过滤软删相机（H3）");
    }
}
