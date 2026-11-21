package com.wms.device.infra.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.wms.shared.util.LogThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 相机域仓储（DDD P3 批次③ 收敛）。
 *
 * <p>承载 {@code camera_status}（运行状态快照）与 {@code dim_camera}（相机档案）
 * 两张表的直接 SQL。SQL 均自 {@code CameraDashboardController} <b>原样搬入</b>，
 * 行为不变。
 *
 * <p><b>count 兜底语义</b>：原 Controller 的 {@code count()} 私有方法内部
 * catch 掉所有异常并返回 0 —— 这是<b>有意的设计</b>（依赖检查失败不应阻断
 * 业务流程），本类 {@link #countOrZero} 完整保留该语义，<b>不可改成抛异常</b>。
 */
@Repository
public class CameraDirectoryRepository {

    private static final Logger log = LoggerFactory.getLogger(CameraDirectoryRepository.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== dim_camera 相机档案 ====================

    /**
     * 按 camera_code 查 dim_camera.camera_id（设备令牌校验需要数字主键）。
     *
     * <p>设备先于建档上线时返回 {@code null}，调用方按「未配置令牌」处理，
     * 不要当成「档案不存在」直接拒绝 —— 现场新装相机就是先上报后建档。
     */
    public Long findCameraIdByCode(String cameraCode) {
        List<Long> list = jdbcTemplate.queryForList(
                "SELECT camera_id FROM dim_camera WHERE camera_code = ? AND IFNULL(is_deleted,0)=0 LIMIT 1",
                Long.class, cameraCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 某相机的有效令牌（未禁用、未过期）。
     *
     * <p>返回空集合表示该相机<b>未配置</b>令牌，与「令牌错误」是两件事 ——
     * 前者由 {@code camera.token.strict} 决定放行还是拒绝，后者一律拒绝。
     */
    public List<Map<String, Object>> listActiveCameraTokens(Long cameraId) {
        return jdbcTemplate.queryForList(
                "SELECT token_hash FROM cfg_camera_token "
                        + "WHERE camera_id = ? AND is_enabled = 1 "
                        + "AND (expire_time IS NULL OR expire_time > NOW())",
                cameraId);
    }

    /** 按相机编码查档案数（排除已删除） */
    public int countDimCameraByCode(String cameraCode) {
        return countOrZero("SELECT COUNT(*) FROM dim_camera WHERE camera_code = ? AND IFNULL(is_deleted,0)=0", cameraCode);
    }

    /** 按相机编码查档案数（排除指定相机与已删除） */
    public int countDimCameraByCodeExcept(String cameraCode, Long cameraId) {
        return countOrZero("SELECT COUNT(*) FROM dim_camera WHERE camera_code = ? AND camera_id <> ? AND IFNULL(is_deleted,0)=0",
                cameraCode, cameraId);
    }

    /** 有效相机档案总数（License 配额判定用，只数未删除的配置行）。 */
    public int countDimCameras() {
        return countOrZero("SELECT COUNT(*) FROM dim_camera WHERE IFNULL(is_deleted,0)=0");
    }

    /**
     * 写入 / 更新相机档案（按 camera_id 主键 upsert）。
     *
     * <p>SQL 与 {@code ConfigImportRepository.upsertDimCamera} 同源，
     * 按「SQL 原样搬运、零优化」原则各自保留；P4 抽跨域接口时统一收敛。
     *
     * <p>★ 2026-11-05 新增 {@code cameraType}（相机类型）：
     * REAL=真实产线相机（数据不参与清理），TEST=测试相机（参与清理）。
     * {@code dim_camera} 是相机档案的<b>权威源</b>，清理任务据此判定保护集合。
     */
    public int upsertDimCamera(Long cameraId, Integer factoryId, Integer lineId, Integer workstationId,
                               String cameraCode, String cameraName, String cameraType) {
        return jdbcTemplate.update(
                "INSERT INTO dim_camera(camera_id, factory_id, line_id, workstation_id, camera_code, camera_name, device_id, camera_type, is_enabled, is_deleted) " +
                        "VALUES (?,?,?,?,?,?,?,?,1,0) " +
                        "ON DUPLICATE KEY UPDATE factory_id=VALUES(factory_id), line_id=VALUES(line_id), workstation_id=VALUES(workstation_id), " +
                        "camera_code=VALUES(camera_code), camera_name=VALUES(camera_name), device_id=VALUES(device_id), " +
                        "camera_type=VALUES(camera_type), is_enabled=1, is_deleted=0",
                cameraId, factoryId, lineId, workstationId, cameraCode, cameraName, cameraCode, cameraType);
    }

    /**
     * 按 camera_code 读取相机的类型（{@code dim_camera} 权威源）。
     *
     * <p>用于更新场景：改其他属性时若请求未带 cameraType，保留原值，
     * 避免把真实相机意外降级成测试相机。
     *
     * @return REAL / TEST；查不到返回 null
     */
    public String findCameraTypeByCode(String cameraCode) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT camera_type FROM dim_camera WHERE camera_code = ? LIMIT 1",
                    String.class, cameraCode);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("读取 dim_camera.camera_type 失败: cameraCode={}, error={}", cameraCode, e.getMessage());
            return null;
        }
    }

    /** 相机档案变更后同步映射表的工厂/产线/工位/编码 */
    public int syncMappingLocation(Integer factoryId, Integer lineId, Integer workstationId, String cameraCode, Long cameraId) {
        return jdbcTemplate.update(
                "UPDATE cfg_camera_defect_mapping SET factory_id=?, line_id=?, workstation_id=?, camera_code=? WHERE camera_id=?",
                factoryId, lineId, workstationId, cameraCode, cameraId);
    }

    /** 逻辑删除相机档案（停用 + 标记删除） */
    public int softDeleteDimCamera(Long cameraId) {
        return jdbcTemplate.update(
                "UPDATE dim_camera SET is_deleted = 1, is_enabled = 0, update_time = NOW() WHERE camera_id = ?", cameraId);
    }

    // ==================== camera_status 状态快照 ====================

    /** 逻辑删除相机状态行（停用） */
    public int softDeleteCameraStatus(Long id) {
        return jdbcTemplate.update("UPDATE camera_status SET is_active = 0, update_time = NOW() WHERE id = ?", id);
    }

    // ==================== 级联清理 ====================

    /** 清理相机缺陷映射（避免孤儿数据） */
    public int deleteDefectMappings(Long cameraId) {
        return jdbcTemplate.update("DELETE FROM cfg_camera_defect_mapping WHERE camera_id = ?", cameraId);
    }

    /** 清理相机设备令牌（避免孤儿数据） */
    public int deleteCameraTokens(Long cameraId) {
        return jdbcTemplate.update("DELETE FROM cfg_camera_token WHERE camera_id = ?", cameraId);
    }

    // ==================== 依赖检查 ====================

    /** 缺陷图片记录数（按相机编码或名称） */
    public int countDefectImageFiles(String cameraCode, String cameraName) {
        return countOrZero("SELECT COUNT(*) FROM defect_image_file WHERE camera_id = ? OR camera_name = ?",
                cameraCode, cameraName);
    }

    /** 兼容表 camera_images 的图片记录数（按相机名称） */
    public int countCompatImages(String cameraName) {
        return countOrZero("SELECT COUNT(*) FROM camera_images WHERE camera_name = ?", cameraName);
    }

    /** 缺陷框记录数（按相机编码或名称） */
    public int countDefectImageBoxes(String cameraCode, String cameraName) {
        return countOrZero("SELECT COUNT(*) FROM defect_image_box b JOIN defect_image_file f ON b.image_id = f.image_id WHERE f.camera_id = ? OR f.camera_name = ?",
                cameraCode, cameraName);
    }

    /** 相机缺陷映射数 */
    public int countDefectMappings(Long cameraId) {
        return countOrZero("SELECT COUNT(*) FROM cfg_camera_defect_mapping WHERE camera_id = ?", cameraId);
    }

    /** 相机设备令牌数 */
    public int countCameraTokens(Long cameraId) {
        return countOrZero("SELECT COUNT(*) FROM cfg_camera_token WHERE camera_id = ?", cameraId);
    }

    /** 相机心跳记录数（按相机编码） */
    public int countHeartbeats(String cameraCode) {
        return countOrZero("SELECT COUNT(*) FROM fact_camera_heartbeat WHERE camera_code = ?", cameraCode);
    }

    /**
     * 写一条心跳历史到 {@code fact_camera_heartbeat}（评审 P1-1，2026-11-21）。
     *
     * <p>表在 02_final_schema_p0.sql 已建、索引齐全（idx_camera_time/status_time/product_time），
     * 但此前**只有 countHeartbeats 读、没有任何写入路径** → 设备温度/状态趋势无法回溯。
     *
     * <p><b>热路径纪律</b>：心跳是 5 秒/台的高频链路，本方法<b>吞掉所有异常只记节流 WARN</b>，
     * 绝不把写历史失败冒泡到心跳主流程（相机上报/告警不能因历史写入失败而 500）。
     * 调用方按相机节流到「每分钟最多 1 条」，避免日增数十万行。
     *
     * @return 是否写入成功（供调用方统计/节流，不影响主流程）
     */
    public boolean insertHeartbeatHistory(Long cameraId, String cameraCode, Long productId, String productName,
                                          java.time.LocalDateTime reportTime, Integer cameraStatus,
                                          Long recognizeCount, Long correctCount, Long abnormalCount,
                                          java.math.BigDecimal cpuUsage, java.math.BigDecimal memoryUsage,
                                          java.math.BigDecimal temperature, String errorMessage) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO fact_camera_heartbeat(camera_id, camera_code, product_id, product_name, " +
                            "report_time, camera_status, recognize_count, correct_count, abnormal_count, " +
                            "cpu_usage, memory_usage, temperature, error_message, create_time) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())",
                    cameraId == null ? 0L : cameraId, cameraCode,
                    productId, productName,
                    java.sql.Timestamp.valueOf(reportTime), cameraStatus,
                    recognizeCount == null ? 0L : recognizeCount,
                    correctCount == null ? 0L : correctCount,
                    abnormalCount == null ? 0L : abnormalCount,
                    cpuUsage, memoryUsage, temperature, errorMessage);
            return true;
        } catch (DataAccessException e) {
            if (LogThrottle.allow("heartbeat-history-db:" + cameraCode, 10 * 60 * 1000L)) {
                log.warn("写心跳历史失败（不影响上报主流程，10 分钟内只记一次）: {}", e.getMessage());
            }
            return false;
        } catch (Exception e) {
            if (LogThrottle.allow("heartbeat-history-unknown:" + cameraCode, 10 * 60 * 1000L)) {
                log.warn("写心跳历史异常（不影响上报主流程）: {}", e.getMessage());
            }
            return false;
        }
    }

    /**
     * 查询某相机近 N 小时的温度/状态采样（供设备健康趋势）。
     *
     * <p>按 report_time 升序返回，最多 limit 条；表缺失时返回空列表（降级）。
     */
    public List<Map<String, Object>> listHeartbeatTrend(String cameraCode, java.time.LocalDateTime since, int limit) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT report_time AS reportTime, camera_status AS cameraStatus, " +
                            "temperature, cpu_usage AS cpuUsage, memory_usage AS memoryUsage, " +
                            "recognize_count AS recognizeCount, correct_count AS correctCount, abnormal_count AS abnormalCount " +
                            "FROM fact_camera_heartbeat WHERE camera_code = ? AND report_time >= ? " +
                            "ORDER BY report_time ASC LIMIT ?",
                    cameraCode, java.sql.Timestamp.valueOf(since), limit);
        } catch (DataAccessException e) {
            if (LogThrottle.allow("heartbeat-trend-db:" + cameraCode, 10 * 60 * 1000L)) {
                log.warn("查心跳趋势失败（降级为空）: {}", e.getMessage());
            }
            return java.util.Collections.emptyList();
        }
    }

    // ==================== DDL ====================

    /**
     * 检查并新增 {@code camera_status} 的列。
     *
     * <p>用 INFORMATION_SCHEMA 判断列是否存在（不能用进程内 AtomicBoolean，
     * 那是 P5 DDL 收敛要处理的问题）。
     *
     * <p><b>异常语义保持原样</b>：原实现把 {@code catch (DataAccessException)}
     * 放在方法<b>内部</b>，失败只记 WARN 然后继续 —— 字段缺失不影响启动，
     * 由后续逻辑兜底。本类完整保留该行为（不向外抛异常）。
     */
    public void ensureCameraStatusColumn(String columnName, String alterSql) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'camera_status' AND COLUMN_NAME = ?",
                    Integer.class,
                    columnName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(alterSql);
            }
        } catch (DataAccessException e) {
            log.warn("检查/新增 camera_status.{} 字段失败: {}", columnName, e.getMessage());
        }
    }

    /**
     * 检查并新增 {@code dim_camera} 的列。
     *
     * <p>异常语义与 {@link #ensureCameraStatusColumn} 一致：失败只记 WARN 后继续，
     * 字段缺失不影响启动（由后续逻辑兜底）。
     */
    public void ensureDimCameraColumn(String columnName, String alterSql) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dim_camera' AND COLUMN_NAME = ?",
                    Integer.class,
                    columnName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(alterSql);
            }
        } catch (DataAccessException e) {
            log.warn("检查/新增 dim_camera.{} 字段失败: {}", columnName, e.getMessage());
        }
    }

    // ==================== 通用 ====================

    /**
     * 计数并吞掉异常（返回 0）。
     *
     * <p>原 Controller 私有 {@code count()} 的语义：查询失败时返回 0 而不是抛异常，
     * 避免依赖检查因某张表缺失而阻断删除等业务流程。<b>不可改成抛异常</b>。
     */
    private int countOrZero(String sql, Object... args) {
        try {
            Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
            return value == null ? 0 : value;
        } catch (Exception e) {
            return 0;
        }
    }
}
