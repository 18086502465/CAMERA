package com.wms.analytics.infra.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产线统计查询 Repository（DDD P3 批次④ 收敛 · 查询域）。
 *
 * <p>承接 {@code LineAnalyticsController} 中「统计查询」类的 SQL：
 * KPI、趋势、缺陷排行、工位/相机排行、缺陷透视、严重度分布、
 * 映射异常，以及<b>缺陷图片事实表兜底</b>统计。
 *
 * <p>SQL 均自原 Controller <b>原样搬入</b>，行为不变：
 * <ul>
 *   <li>列别名保持驼峰形式（{@code totalCount} / {@code defectTypeId} ...），前端键结构零变化；</li>
 *   <li>兜底统计的口径保持：{@code okCount=0}、{@code yieldRate=null}
 *       （图片通道无良品样本，良率不可计算）；</li>
 *   <li>{@code defect_image_file.camera_id} 存的是 camera_code，
 *       需经 {@code dim_camera} 关联出维度。</li>
 * </ul>
 */
@Repository
public class LineAnalyticsQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public LineAnalyticsQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 聚合表口径 ====================

    /** KPI 汇总（单行） */
    public Map<String, Object> queryKpi(String qualityTable, String qualityWhere, List<Object> params) {
        return one(
                "SELECT COALESCE(SUM(total_count),0) AS totalCount, COALESCE(SUM(ok_count),0) AS okCount, COALESCE(SUM(ng_count),0) AS ngCount, " +
                        "COALESCE(SUM(defect_count),0) AS defectCount FROM " + qualityTable + " " + qualityWhere,
                params);
    }

    /** 质量趋势（按时间桶） */
    public List<Map<String, Object>> queryTrend(String qualityTable, String timeColumn, String qualityWhere, List<Object> params) {
        return jdbcTemplate.queryForList(
                "SELECT " + timeColumn + " AS timeBucket, SUM(total_count) AS totalCount, SUM(ok_count) AS okCount, SUM(ng_count) AS ngCount, " +
                        "IF(SUM(total_count)>0, ROUND(SUM(ok_count)*100/SUM(total_count),2),0) AS yieldRate " +
                        "FROM " + qualityTable + " " + qualityWhere + " GROUP BY " + timeColumn + " ORDER BY " + timeColumn,
                params.toArray());
    }

    /** 缺陷类型排行（Top 20） */
    public List<Map<String, Object>> queryDefectRanking(String defectTable, String defectWhere, List<Object> params) {
        return jdbcTemplate.queryForList(
                "SELECT defect_type_id AS defectTypeId, defect_code AS defectCode, defect_name AS defectName, severity, SUM(defect_count) AS defectCount " +
                        "FROM " + defectTable + " " + defectWhere + " GROUP BY defect_type_id, defect_code, defect_name, severity ORDER BY defectCount DESC LIMIT 20",
                params.toArray());
    }

    /** 工位排行（Top 20） */
    public List<Map<String, Object>> queryStationRanking(String qualityTable, String stationWhere, List<Object> params) {
        return jdbcTemplate.queryForList(
                "SELECT q.workstation_id AS workstationId, w.station_name AS workstationName, SUM(q.total_count) AS totalCount, SUM(q.ok_count) AS okCount, SUM(q.ng_count) AS ngCount, " +
                        "IF(SUM(q.total_count)>0, ROUND(SUM(q.ok_count)*100/SUM(q.total_count),2),0) AS yieldRate " +
                        "FROM " + qualityTable + " q LEFT JOIN dim_workstation w ON q.workstation_id = w.workstation_id " +
                        stationWhere +
                        " GROUP BY q.workstation_id, w.station_name ORDER BY ngCount DESC, totalCount DESC LIMIT 20",
                params.toArray());
    }

    /** 相机排行（Top 20） */
    public List<Map<String, Object>> queryCameraRanking(String qualityTable, String cameraWhere, List<Object> params) {
        return jdbcTemplate.queryForList(
                "SELECT q.camera_id AS cameraId, c.camera_name AS cameraName, c.camera_code AS cameraCode, SUM(q.total_count) AS totalCount, SUM(q.ok_count) AS okCount, SUM(q.ng_count) AS ngCount, " +
                        "IF(SUM(q.total_count)>0, ROUND(SUM(q.ok_count)*100/SUM(q.total_count),2),0) AS yieldRate " +
                        "FROM " + qualityTable + " q LEFT JOIN dim_camera c ON q.camera_id = c.camera_id " +
                        cameraWhere +
                        " GROUP BY q.camera_id, c.camera_name, c.camera_code ORDER BY ngCount DESC, totalCount DESC LIMIT 20",
                params.toArray());
    }

    /** 缺陷透视（产品 × 缺陷类型） */
    public List<Map<String, Object>> queryDefectPivot(String defectTable, String defectWhere, List<Object> params) {
        return jdbcTemplate.queryForList(
                "SELECT product_id AS productId, defect_type_id AS defectTypeId, defect_code AS defectCode, defect_name AS defectName, SUM(defect_count) AS defectCount " +
                        "FROM " + defectTable + " " + defectWhere + " GROUP BY product_id, defect_type_id, defect_code, defect_name ORDER BY product_id, defectCount DESC",
                params.toArray());
    }

    /** 严重度分布（高/中/低） */
    public List<Map<String, Object>> querySeverityDistribution(String defectTable, String defectWhere, List<Object> params) {
        return jdbcTemplate.queryForList(
                "SELECT UPPER(IFNULL(severity,'UNKNOWN')) AS severity, SUM(defect_count) AS defectCount " +
                        "FROM " + defectTable + " " + defectWhere + " GROUP BY UPPER(IFNULL(severity,'UNKNOWN')) ORDER BY defectCount DESC",
                params.toArray());
    }

    /** 待处理的映射异常（Top 10） */
    public List<Map<String, Object>> queryOpenMappingExceptions() {
        return safeList(
                "SELECT exception_id AS exceptionId, camera_code AS cameraCode, product_name AS productName, source_code AS sourceCode, exception_type AS exceptionType, exception_message AS exceptionMessage, create_time AS createTime " +
                        "FROM fact_inspection_mapping_exception WHERE status = 'OPEN' ORDER BY create_time DESC LIMIT 10");
    }

    // ==================== 缺陷图片事实表口径（聚合表无数据时的兜底） ====================

    /** 兜底 KPI（缺陷图片张数 / 缺陷框数 / 涉及相机工位数） */
    public Map<String, Object> queryDefectImageKpi(String joinCam, String where, List<Object> params) {
        return one(
                "SELECT COUNT(1) AS imageCount, COALESCE(SUM(f.defect_count),0) AS defectCount, " +
                        "COALESCE(SUM(f.high_count),0) AS highCount, COALESCE(SUM(f.medium_count),0) AS mediumCount, " +
                        "COALESCE(SUM(f.low_count),0) AS lowCount, COUNT(DISTINCT c.camera_id) AS cameraCount, " +
                        "COUNT(DISTINCT c.workstation_id) AS workstationCount " +
                        "FROM defect_image_file f " + joinCam + where,
                params);
    }

    /** 兜底趋势（按小时聚合缺陷图与缺陷数） */
    public List<Map<String, Object>> queryDefectImageTrend(String joinCam, String where, List<Object> params) {
        return jdbcTemplate.queryForList(
                "SELECT DATE_FORMAT(f.capture_time,'%Y-%m-%d %H:00:00') AS timeBucket, " +
                        "COUNT(1) AS totalCount, 0 AS okCount, COUNT(1) AS ngCount, " +
                        // ★ 2026-11-05：图片通道只上报缺陷图，**没有良品样本** → 良率不可计算。
                        //   原先写死 `0 AS yieldRate`，前端会照着画一条 0% 的假良率曲线，
                        //   误导现场以为"良率为 0"。改为 NULL，由前端按「不可计算」处理。
                        "COALESCE(SUM(f.defect_count),0) AS defectCount, NULL AS yieldRate " +
                        "FROM defect_image_file f " + joinCam + where +
                        " GROUP BY DATE_FORMAT(f.capture_time,'%Y-%m-%d %H:00:00') ORDER BY timeBucket",
                params.toArray());
    }

    /** 兜底缺陷类型排行（按 defect_image_box 的 defect_type，现场口径为中文缺陷名） */
    public List<Map<String, Object>> queryDefectImageRanking(String joinCam, String where, String sevWhere, List<Object> params) {
        return jdbcTemplate.queryForList(
                "SELECT b.defect_type AS defectName, b.defect_type AS defectCode, 0 AS defectTypeId, " +
                        "b.severity AS severity, COUNT(1) AS defectCount " +
                        "FROM defect_image_box b " +
                        " JOIN defect_image_file f ON f.image_id = b.image_id " + joinCam + where + sevWhere +
                        " GROUP BY b.defect_type, b.severity ORDER BY defectCount DESC LIMIT 20",
                params.toArray());
    }

    /** 兜底工位排行 */
    public List<Map<String, Object>> queryDefectImageStationRanking(String joinCam, String where, List<Object> params) {
        return jdbcTemplate.queryForList(
                "SELECT c.workstation_id AS workstationId, w.station_name AS workstationName, " +
                        "COUNT(1) AS totalCount, 0 AS okCount, COUNT(1) AS ngCount, " +
                        "COALESCE(SUM(f.defect_count),0) AS defectCount, 0 AS yieldRate " +
                        "FROM defect_image_file f " + joinCam +
                        // ★ 2026-11-05 修正：原 SQL 写成 w.station_id，
                        //   但 dim_workstation 的主键列是 **workstation_id**（无 station_id 列）
                        //   → MySQL 报 "Unknown column 'w.station_id' in 'on clause'"，
                        //   兜底路径（聚合表无数据时）必然 500。此处按真实列名修正。
                        " LEFT JOIN dim_workstation w ON w.workstation_id = c.workstation_id " + where +
                        " GROUP BY c.workstation_id, w.station_name ORDER BY ngCount DESC, totalCount DESC LIMIT 20",
                params.toArray());
    }

    /** 兜底相机排行 */
    public List<Map<String, Object>> queryDefectImageCameraRanking(String joinCam, String where, List<Object> params) {
        return jdbcTemplate.queryForList(
                "SELECT c.camera_id AS cameraId, c.camera_name AS cameraName, c.camera_code AS cameraCode, " +
                        "COUNT(1) AS totalCount, 0 AS okCount, COUNT(1) AS ngCount, " +
                        "COALESCE(SUM(f.defect_count),0) AS defectCount, 0 AS yieldRate " +
                        "FROM defect_image_file f " + joinCam + where +
                        " GROUP BY c.camera_id, c.camera_name, c.camera_code ORDER BY ngCount DESC, totalCount DESC LIMIT 20",
                params.toArray());
    }

    /** 兜底产品缺陷透视（图片通道无产品维度，用缺陷类型作列） */
    public List<Map<String, Object>> queryDefectImagePivot(String joinCam, String where, String sevWhere, List<Object> params) {
        return jdbcTemplate.queryForList(
                "SELECT 0 AS productId, 0 AS defectTypeId, b.defect_type AS defectCode, b.defect_type AS defectName, " +
                        "COUNT(1) AS defectCount " +
                        "FROM defect_image_box b " +
                        " JOIN defect_image_file f ON f.image_id = b.image_id " + joinCam + where + sevWhere +
                        " GROUP BY b.defect_type ORDER BY defectCount DESC LIMIT 30",
                params.toArray());
    }

    /** 兜底严重度分布 */
    public List<Map<String, Object>> queryDefectImageSeverityDistribution(String joinCam, String where, String sevWhere, List<Object> params) {
        return jdbcTemplate.queryForList(
                "SELECT UPPER(IFNULL(b.severity,'UNKNOWN')) AS severity, COUNT(1) AS defectCount " +
                        "FROM defect_image_box b " +
                        " JOIN defect_image_file f ON f.image_id = b.image_id " + joinCam + where + sevWhere +
                        " GROUP BY UPPER(IFNULL(b.severity,'UNKNOWN')) ORDER BY defectCount DESC",
                params.toArray());
    }

    /**
     * 时段×缺陷类型热力图（图片通道，2026-11-14 看板 A）。
     *
     * <p>JOIN defect_image_box（非 LEFT JOIN）：无缺陷框的图片本就没有类型，不参与热力图；
     * 同时天然排除「图片已删、框未删」的孤儿框。
     */
    public List<Map<String, Object>> queryDefectImageHourlyHeatmap(String joinCam, String where, List<Object> params) {
        return jdbcTemplate.queryForList(
                "SELECT HOUR(f.capture_time) AS hourOfDay, b.defect_type AS defectType, COUNT(1) AS defectCount " +
                        "FROM defect_image_file f " + joinCam +
                        " JOIN defect_image_box b ON b.image_id = f.image_id " + where +
                        " GROUP BY HOUR(f.capture_time), b.defect_type " +
                        " ORDER BY defectCount DESC, hourOfDay ASC LIMIT 360",
                params.toArray());
    }

    /**
     * 班次×缺陷类型对比（图片通道，2026-11-14 看板 A）。
     *
     * <p>dayWindow/nightWindow 是 'HH:mm:ss' 字面量，由调用方从 dim_shift 读出后格式化拼接
     * （值来自数据库而非用户输入，无注入面）。CASE 放在 SELECT 里，占位符顺序先于 WHERE，
     * 所以不参数化、直接拼接，避免参数顺序错位。
     */
    public List<Map<String, Object>> queryDefectImageShiftCompare(String joinCam, String where, List<Object> params,
                                                                  String dayWindow, String nightWindow) {
        return jdbcTemplate.queryForList(
                "SELECT b.defect_type AS defectType, " +
                        "SUM(CASE WHEN TIME(f.capture_time) >= '" + dayWindow + "' AND TIME(f.capture_time) < '" + nightWindow + "' THEN 1 ELSE 0 END) AS dayCount, " +
                        "SUM(CASE WHEN TIME(f.capture_time) >= '" + nightWindow + "' OR TIME(f.capture_time) < '" + dayWindow + "' THEN 1 ELSE 0 END) AS nightCount, " +
                        "COUNT(1) AS totalCount " +
                        "FROM defect_image_file f " + joinCam +
                        " JOIN defect_image_box b ON b.image_id = f.image_id " + where +
                        " GROUP BY b.defect_type ORDER BY totalCount DESC LIMIT 12",
                params.toArray());
    }

    /** 窗口图片计数（环比口径：当前时间窗 vs 等长前移窗口） */
    public long countDefectImages(String joinCam, String where, List<Object> params) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM defect_image_file f " + joinCam + where, Long.class, params.toArray());
        return count == null ? 0L : count;
    }

    /** 按主键取班次定义（时间窗推导用） */
    public Map<String, Object> findShiftById(Long shiftId) {
        return one("SELECT shift_id AS shiftId, shift_name AS shiftName, start_time AS startTime, " +
                "end_time AS endTime, cross_day AS crossDay FROM dim_shift WHERE shift_id = ?",
                Collections.singletonList(shiftId));
    }

    /** 班次对比分桶定义：不跨天且最早开始的为「白班」，跨天的为「夜班」 */
    public List<Map<String, Object>> listShiftsForCompare() {
        return jdbcTemplate.queryForList(
                "SELECT shift_id AS shiftId, shift_name AS shiftName, start_time AS startTime, " +
                        "end_time AS endTime, cross_day AS crossDay FROM dim_shift WHERE is_enabled = 1 " +
                        "ORDER BY cross_day ASC, start_time ASC LIMIT 2");
    }

    // ==================== 通用 ====================

    /** 取单行（无结果返回空 Map） */
    public Map<String, Object> one(String sql, List<Object> params) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    /** 查询失败时返回空列表（不阻断主流程） */
    public List<Map<String, Object>> safeList(String sql) {
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
