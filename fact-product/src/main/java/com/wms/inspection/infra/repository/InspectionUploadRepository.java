package com.wms.inspection.infra.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 检测批次上报仓储（DDD P3 批次⑥ 收敛）。
 *
 * <p>承载 {@code fact_inspection_batch} / {@code fact_inspection_defect_count} /
 * {@code fact_inspection_mapping_exception} 三张事实表，以及
 * {@code agg_quality_30m} / {@code agg_defect_type_30m} 两个聚合表的写入，
 * 以及上报流程中需要查的维表（相机 / 产品 / 映射 / 班次 / 令牌）。
 *
 * <p>SQL 均自 {@code InspectionUploadServiceImpl} <b>原样搬入</b>，行为不变。
 *
 * <p><b>语义保留要点</b>：
 * <ul>
 *   <li>{@code shift_id} 为空时写 <b>0</b> 而非 NULL；</li>
 *   <li>{@code unmapped_defect_count} 固定写 0，{@code status} 固定 'ACCEPTED'；</li>
 *   <li>{@code counter_mode} 固定 'INCREMENTAL'；</li>
 *   <li>令牌表未初始化时<b>不阻断</b>（捕获异常后仅校验 Token 必填）；</li>
 *   <li>{@code syncLegacyCameraStatus} 失败<b>静默忽略</b>（旧兼容表可废弃）。</li>
 * </ul>
 */
@Repository
public class InspectionUploadRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 令牌校验 ====================

    /** 查设备令牌（无结果返回空列表） */
    public List<Map<String, Object>> listCameraTokens(Long cameraId) {
        return jdbcTemplate.queryForList(
                "SELECT token_hash, expire_time FROM cfg_camera_token " +
                        "WHERE camera_id = ? AND is_enabled = 1 AND (expire_time IS NULL OR expire_time > NOW())",
                cameraId);
    }

    // ==================== 维表查询 ====================

    /** 按相机编码或设备 ID 查相机档案 */
    public Map<String, Object> findCamera(String cameraCode, String deviceIdHeader) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT camera_id, camera_code, camera_name, factory_id, line_id, workstation_id " +
                        "FROM dim_camera WHERE is_deleted = 0 AND is_enabled = 1 " +
                        "AND (camera_code = ? OR device_id = ?) LIMIT 1",
                cameraCode, deviceIdHeader);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按产品名查产品 */
    public Map<String, Object> findProduct(String productName) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT product_id, product_name, product_code FROM dim_product " +
                        "WHERE is_deleted = 0 AND is_enabled = 1 AND product_name = ? LIMIT 1",
                productName);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查生效中的相机缺陷映射（优先精确匹配产品） */
    public Map<String, Object> findMapping(Long cameraId, Long productId, String sourceCode, LocalDateTime reportTime) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT mapping_id, source_code, defect_type_id, defect_code, defect_name, " +
                        "position_id, position_code, position_name, severity, is_ng " +
                        "FROM cfg_camera_defect_mapping " +
                        "WHERE camera_id = ? AND source_code = ? AND is_enabled = 1 " +
                        "AND (product_id = ? OR product_id IS NULL) " +
                        "AND effective_start <= ? AND (effective_end IS NULL OR effective_end > ?) " +
                        "ORDER BY CASE WHEN product_id = ? THEN 0 ELSE 1 END, effective_start DESC LIMIT 1",
                cameraId, sourceCode, productId, Timestamp.valueOf(reportTime), Timestamp.valueOf(reportTime), productId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查已启用的班次（按班次 ID 排序） */
    public List<Map<String, Object>> listEnabledShifts() {
        return jdbcTemplate.queryForList(
                "SELECT shift_id, start_time, end_time, cross_day FROM dim_shift WHERE is_enabled = 1 ORDER BY shift_id");
    }

    /** 统计已存在的同批次记录数（幂等检查） */
    public Integer countExistingBatch(Long cameraId, String batchId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fact_inspection_batch WHERE camera_id = ? AND batch_id = ?",
                Integer.class, cameraId, batchId);
    }

    /** 按相机与批次号查已存在的批次 ID（无结果返回 null） */
    public Long queryExistingBatchId(Long cameraId, String batchId) {
        try {
            Number value = jdbcTemplate.queryForObject(
                    "SELECT inspection_batch_id FROM fact_inspection_batch WHERE camera_id = ? AND batch_id = ? LIMIT 1",
                    Number.class, cameraId, batchId);
            return value == null ? null : value.longValue();
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ==================== 写入 ====================

    /** 插入检测批次，返回 inspection_batch_id */
    public Long insertBatch(String batchId, String requestId, String schemaVersion,
                            Long cameraId, String cameraCode, String cameraName,
                            Long productId, String productName,
                            Long factoryId, Long lineId, Long workstationId, Long shiftId,
                            LocalDateTime reportTime, LocalDateTime startTime, LocalDateTime endTime,
                            long total, long ok, long ng, long mappedDefectCount, String rawJson) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO fact_inspection_batch " +
                            "(batch_id, request_id, schema_version, camera_id, camera_code, camera_name, product_id, product_name, " +
                            "factory_id, line_id, workstation_id, shift_id, report_time, start_time, end_time, counter_mode, " +
                            "total_count, ok_count, ng_count, mapped_defect_count, unmapped_defect_count, status, raw_json) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS JSON))",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchId);
            ps.setString(2, requestId);
            ps.setString(3, schemaVersion);
            ps.setLong(4, cameraId);
            ps.setString(5, cameraCode);
            ps.setString(6, cameraName);
            ps.setLong(7, productId);
            ps.setString(8, productName);
            ps.setLong(9, factoryId);
            ps.setLong(10, lineId);
            ps.setLong(11, workstationId);
            ps.setLong(12, shiftId == null ? 0L : shiftId);
            ps.setTimestamp(13, Timestamp.valueOf(reportTime));
            ps.setTimestamp(14, Timestamp.valueOf(startTime));
            ps.setTimestamp(15, Timestamp.valueOf(endTime));
            ps.setString(16, "INCREMENTAL");
            ps.setLong(17, total);
            ps.setLong(18, ok);
            ps.setLong(19, ng);
            ps.setLong(20, mappedDefectCount);
            ps.setLong(21, 0L);
            ps.setString(22, "ACCEPTED");
            ps.setString(23, rawJson);
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null ? queryExistingBatchId(cameraId, batchId) : key.longValue();
    }

    /** 插入缺陷计数事实 */
    public void insertDefectCount(Long inspectionBatchId, String batchId, Long cameraId, String cameraCode,
                                  Long productId, String productName, Long factoryId, Long lineId, Long workstationId,
                                  Long shiftId, LocalDateTime reportTime, LocalDateTime bucket10m, LocalDateTime bucket30m,
                                  Map<String, Object> mapping) {
        jdbcTemplate.update(
                "INSERT INTO fact_inspection_defect_count " +
                        "(inspection_batch_id, batch_id, camera_id, camera_code, product_id, product_name, factory_id, line_id, workstation_id, shift_id, " +
                        "report_time, time_bucket_10m, time_bucket_30m, source_code, mapping_id, defect_type_id, defect_code, defect_name, " +
                        "position_id, position_code, position_name, severity, defect_count, is_ng) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                inspectionBatchId, batchId, cameraId, cameraCode, productId, productName, factoryId, lineId, workstationId,
                shiftId == null ? 0L : shiftId, Timestamp.valueOf(reportTime), Timestamp.valueOf(bucket10m), Timestamp.valueOf(bucket30m),
                str(mapping.get("_source_code")), longVal(mapping.get("mapping_id")), longVal(mapping.get("defect_type_id")),
                str(mapping.get("defect_code")), str(mapping.get("defect_name")), longOrNull(mapping.get("position_id")),
                strOrNull(mapping.get("position_code")), strOrNull(mapping.get("position_name")), str(mapping.get("severity")),
                longVal(mapping.get("_count")), intVal(mapping.get("is_ng"), 1));
    }

    /** 写入质量聚合（upsert 累加 + 回算良率/NG 率） */
    public void upsertQualityAgg(LocalDateTime bucket, Long factoryId, Long lineId, Long workstationId,
                                 Long cameraId, Long productId, Long shiftId,
                                 long total, long ok, long ng, long defect) {
        jdbcTemplate.update(
                "INSERT INTO agg_quality_30m " +
                        "(time_bucket, factory_id, line_id, workstation_id, camera_id, product_id, shift_id, total_count, ok_count, ng_count, defect_count, yield_rate, ng_rate) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE total_count = total_count + VALUES(total_count), ok_count = ok_count + VALUES(ok_count), " +
                        "ng_count = ng_count + VALUES(ng_count), defect_count = defect_count + VALUES(defect_count)",
                Timestamp.valueOf(bucket), factoryId, lineId, workstationId, cameraId, productId, shiftId == null ? 0L : shiftId,
                total, ok, ng, defect, rate(ok, total), rate(ng, total));
        jdbcTemplate.update(
                "UPDATE agg_quality_30m SET yield_rate = IF(total_count > 0, ok_count / total_count, 0), " +
                        "ng_rate = IF(total_count > 0, ng_count / total_count, 0) " +
                        "WHERE time_bucket = ? AND factory_id = ? AND line_id = ? AND workstation_id = ? AND camera_id = ? AND product_id = ? AND shift_id = ?",
                Timestamp.valueOf(bucket), factoryId, lineId, workstationId, cameraId, productId, shiftId == null ? 0L : shiftId);
    }

    /** 写入缺陷类型聚合（upsert 累加） */
    public void upsertDefectAgg(LocalDateTime bucket, Long factoryId, Long lineId, Long workstationId,
                                Long cameraId, Long productId, Long shiftId, Map<String, Object> mapping) {
        jdbcTemplate.update(
                "INSERT INTO agg_defect_type_30m " +
                        "(time_bucket, factory_id, line_id, workstation_id, camera_id, product_id, shift_id, defect_type_id, defect_code, defect_name, " +
                        "position_id, position_code, severity, defect_count) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE defect_count = defect_count + VALUES(defect_count)",
                Timestamp.valueOf(bucket), factoryId, lineId, workstationId, cameraId, productId, shiftId == null ? 0L : shiftId,
                longVal(mapping.get("defect_type_id")), str(mapping.get("defect_code")), str(mapping.get("defect_name")),
                longOrNull(mapping.get("position_id")), strOrNull(mapping.get("position_code")), str(mapping.get("severity")),
                longVal(mapping.get("_count")));
    }

    /** 写入当前状态（upsert 累加计数） */
    public void upsertCurrentStatus(Long cameraId, String cameraCode, Long productId, String productName,
                                    LocalDateTime reportTime, long total, long ok, long ng) {
        jdbcTemplate.update(
                "INSERT INTO fact_camera_status_current " +
                        "(camera_id, camera_code, product_id, product_name, upload_state, last_upload_time, recognize_count, correct_count, abnormal_count) " +
                        "VALUES (?,?,?,?,?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE product_id = VALUES(product_id), product_name = VALUES(product_name), upload_state = 'ONLINE', " +
                        "last_upload_time = VALUES(last_upload_time), recognize_count = recognize_count + VALUES(recognize_count), " +
                        "correct_count = correct_count + VALUES(correct_count), abnormal_count = abnormal_count + VALUES(abnormal_count)",
                cameraId, cameraCode, productId, productName, "ONLINE", Timestamp.valueOf(reportTime), total, ok, ng);
    }

    /** 同步旧兼容表 camera_status（失败静默忽略 —— 旧表可废弃） */
    public void syncLegacyCameraStatus(String cameraCode, String productName, LocalDateTime reportTime,
                                       long total, long ok, long ng) {
        try {
            jdbcTemplate.update(
                    "UPDATE camera_status SET product_name = ?, report_time = ?, last_upload_at = ?, " +
                            "recognize_count = IFNULL(recognize_count,0) + ?, correct_count = IFNULL(correct_count,0) + ?, abnormal_count = IFNULL(abnormal_count,0) + ?, " +
                            "total_count = IFNULL(total_count,0) + ?, ok_count = IFNULL(ok_count,0) + ?, ng_count = IFNULL(ng_count,0) + ?, update_time = NOW() " +
                            "WHERE camera_code = ?",
                    productName, Timestamp.valueOf(reportTime), Timestamp.valueOf(reportTime),
                    total, ok, ng, total, ok, ng, cameraCode);
        } catch (Exception ignored) {
            // 旧兼容表可废弃，最终模型不依赖该更新。
        }
    }

    /** 记录映射异常 */
    public void recordException(String batchId, String requestId, Long cameraId, String cameraCode, Long productId,
                                String productName, String sourceCode, String exceptionType, String message,
                                String suggestion, Long factoryId, Long lineId, Long workstationId, LocalDateTime reportTime) {
        jdbcTemplate.update(
                "INSERT INTO fact_inspection_mapping_exception " +
                        "(batch_id, request_id, camera_id, camera_code, product_id, product_name, source_code, exception_type, exception_message, suggestion, " +
                        "factory_id, line_id, workstation_id, report_time, status) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                batchId, requestId, cameraId, cameraCode, productId, productName, sourceCode, exceptionType, message, suggestion,
                factoryId, lineId, workstationId, reportTime == null ? null : Timestamp.valueOf(reportTime), "OPEN");
    }

    // ==================== 通用（与原 Service 私有方法同语义） ====================

    private String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private String strOrNull(Object o) { return o == null ? null : String.valueOf(o); }

    private Long longVal(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        return Long.parseLong(String.valueOf(o));
    }

    private Long longOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        String text = String.valueOf(o);
        return text.isEmpty() ? null : Long.parseLong(text);
    }

    private int intVal(Object o, int defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Number) return ((Number) o).intValue();
        String text = String.valueOf(o);
        return text.isEmpty() ? defaultValue : Integer.parseInt(text);
    }

    private double rate(long numerator, long denominator) {
        return denominator > 0 ? (double) numerator / denominator : 0.0;
    }
}
