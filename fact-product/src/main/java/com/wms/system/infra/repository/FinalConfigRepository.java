package com.wms.system.infra.repository;

import com.wms.system.domain.model.CameraDefectMappingRow;
import com.wms.system.domain.model.DefectTypeConfigRow;
import com.wms.system.domain.model.ProductConfigRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最终模型配置仓储（DDD P3 批次② 收敛）。
 *
 * <p>SQL 均自 {@code FinalConfigController} <b>原样搬入</b>，行为不变：
 * <ul>
 *   <li>列表 SQL 的 WHERE 片段拼装与参数顺序保持不变；</li>
 *   <li>分页语义保持 {@code LIMIT ?, ?} + 独立 COUNT；</li>
 *   <li>映射冲突检测 SQL（含 {@code AND mapping_id <> ?} 动态片段）逐字保留。</li>
 * </ul>
 *
 * <p>业务校验（必填项、编码唯一性、时间解析）仍留在 Controller，
 * 本类只负责"取数与写库"。
 */
@Repository
public class FinalConfigRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 产品 ====================

    /** 产品分页列表 */
    public Map<String, Object> queryProductPage(String keyword, Integer enabled, int page, int size) {
        List<Object> params = new ArrayList<>();
        String where = " WHERE is_deleted = 0 ";
        if (!isBlank(keyword)) {
            where += " AND (product_name LIKE ? OR product_code LIKE ? OR brand_name LIKE ?) ";
            String like = "%" + keyword.trim() + "%";
            params.add(like); params.add(like); params.add(like);
        }
        if (enabled != null) {
            where += " AND is_enabled = ? ";
            params.add(enabled);
        }
        return queryPage(
                "SELECT product_id AS productId, product_code AS productCode, product_name AS productName, product_spec AS productSpec, " +
                        "brand_name AS brandName, description, sort_order AS sortOrder, is_enabled AS isEnabled, create_time AS createTime, update_time AS updateTime " +
                        "FROM dim_product " + where + " ORDER BY sort_order ASC, product_id DESC",
                "SELECT COUNT(*) FROM dim_product " + where,
                params, page, size);
    }

    /** 产品编码或名称重复数（新增校验） */
    public int countProductByCodeOrName(String productCode, String productName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dim_product WHERE is_deleted = 0 AND (product_code = ? OR product_name = ?)",
                Integer.class, productCode, productName);
        return count == null ? 0 : count;
    }

    /** 产品编码或名称重复数（更新校验，排除自身） */
    public int countProductByCodeOrNameExcept(Long id, String productCode, String productName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dim_product WHERE is_deleted = 0 AND product_id <> ? AND (product_code = ? OR product_name = ?)",
                Integer.class, id, productCode, productName);
        return count == null ? 0 : count;
    }

    /** 新增产品 */
    public int insertProduct(ProductConfigRow row) {
        return jdbcTemplate.update(
                "INSERT INTO dim_product (product_code, product_name, product_spec, brand_name, description, sort_order, is_enabled) VALUES (?,?,?,?,?,?,?)",
                row.getProductCode(), row.getProductName(), row.getProductSpec(), row.getBrandName(),
                row.getDescription(), row.getSortOrder(), row.getIsEnabled());
    }

    /** 更新产品 */
    public int updateProduct(Long id, ProductConfigRow row) {
        return jdbcTemplate.update(
                "UPDATE dim_product SET product_code=?, product_name=?, product_spec=?, brand_name=?, description=?, sort_order=?, is_enabled=? WHERE product_id=?",
                row.getProductCode(), row.getProductName(), row.getProductSpec(), row.getBrandName(),
                row.getDescription(), row.getSortOrder(), row.getIsEnabled(), id);
    }

    /** 产品改名后联动相机缺陷映射里的冗余产品名 */
    public int updateMappingProductName(Long productId, String productName) {
        return jdbcTemplate.update("UPDATE cfg_camera_defect_mapping SET product_name=? WHERE product_id=?",
                productName, productId);
    }

    /** 产品的启用映射数 */
    public int countProductMappings(Long productId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cfg_camera_defect_mapping WHERE product_id = ? AND is_enabled = 1",
                Integer.class, productId);
        return count == null ? 0 : count;
    }

    /** 产品的检测历史批次数 */
    public int countProductInspectionBatches(Long productId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fact_inspection_batch WHERE product_id = ?",
                Integer.class, productId);
        return count == null ? 0 : count;
    }

    /** 逻辑删除产品 */
    public int softDeleteProduct(Long id) {
        return jdbcTemplate.update("UPDATE dim_product SET is_deleted = 1, is_enabled = 0 WHERE product_id = ?", id);
    }

    // ==================== 缺陷类型 ====================

    /** 缺陷类型分页列表 */
    public Map<String, Object> queryDefectTypePage(Long productId, String keyword, String severity, int page, int size) {
        List<Object> params = new ArrayList<>();
        String where = " WHERE dt.is_deleted = 0 ";
        if (productId != null) { where += " AND dt.product_id = ? "; params.add(productId); }
        if (!isBlank(keyword)) {
            where += " AND (dt.defect_code LIKE ? OR dt.defect_name LIKE ? OR p.product_name LIKE ?) ";
            String like = "%" + keyword.trim() + "%";
            params.add(like); params.add(like); params.add(like);
        }
        if (!isBlank(severity)) { where += " AND dt.default_severity = ? "; params.add(severity.trim()); }
        return queryPage(
                "SELECT dt.defect_type_id AS defectTypeId, dt.product_id AS productId, p.product_name AS productName, " +
                        "dt.defect_code AS defectCode, dt.defect_name AS defectName, dt.defect_category AS defectCategory, " +
                        "dt.default_position_id AS defaultPositionId, dt.default_position_code AS defaultPositionCode, dt.default_position_name AS defaultPositionName, " +
                        "dt.default_severity AS defaultSeverity, dt.is_ng AS isNg, dt.description, dt.sort_order AS sortOrder, dt.is_enabled AS isEnabled, dt.update_time AS updateTime " +
                        "FROM dim_defect_type dt LEFT JOIN dim_product p ON dt.product_id = p.product_id " + where +
                        " ORDER BY COALESCE(p.sort_order,0), dt.sort_order ASC, dt.defect_type_id DESC",
                "SELECT COUNT(*) FROM dim_defect_type dt LEFT JOIN dim_product p ON dt.product_id = p.product_id " + where,
                params, page, size);
    }

    /** 同一产品下缺陷编码重复数（新增校验） */
    public int countDefectTypeByCode(Long productId, String defectCode) {
        Integer count = jdbcTemplate.queryForObject(
                productId == null
                        ? "SELECT COUNT(*) FROM dim_defect_type WHERE is_deleted = 0 AND product_id IS NULL AND defect_code = ?"
                        : "SELECT COUNT(*) FROM dim_defect_type WHERE is_deleted = 0 AND product_id = ? AND defect_code = ?",
                Integer.class,
                productId == null ? new Object[]{defectCode} : new Object[]{productId, defectCode});
        return count == null ? 0 : count;
    }

    /** 同一产品下缺陷编码重复数（更新校验，排除自身） */
    public int countDefectTypeByCodeExcept(Long id, Long productId, String defectCode) {
        Integer count = jdbcTemplate.queryForObject(
                productId == null
                        ? "SELECT COUNT(*) FROM dim_defect_type WHERE is_deleted = 0 AND product_id IS NULL AND defect_code = ? AND defect_type_id <> ?"
                        : "SELECT COUNT(*) FROM dim_defect_type WHERE is_deleted = 0 AND product_id = ? AND defect_code = ? AND defect_type_id <> ?",
                Integer.class,
                productId == null ? new Object[]{defectCode, id} : new Object[]{productId, defectCode, id});
        return count == null ? 0 : count;
    }

    /** 新增缺陷类型 */
    public int insertDefectType(DefectTypeConfigRow row) {
        return jdbcTemplate.update(
                "INSERT INTO dim_defect_type (product_id, defect_code, defect_name, defect_category, default_position_id, default_position_code, default_position_name, " +
                        "default_severity, is_ng, description, sort_order, is_enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                row.getProductId(), row.getDefectCode(), row.getDefectName(), row.getDefectCategory(),
                row.getDefaultPositionId(), row.getDefaultPositionCode(), row.getDefaultPositionName(),
                row.getDefaultSeverity(), row.getIsNg(), row.getDescription(), row.getSortOrder(), row.getIsEnabled());
    }

    /** 更新缺陷类型 */
    public int updateDefectType(Long id, DefectTypeConfigRow row) {
        return jdbcTemplate.update(
                "UPDATE dim_defect_type SET product_id=?, defect_code=?, defect_name=?, defect_category=?, default_position_id=?, default_position_code=?, " +
                        "default_position_name=?, default_severity=?, is_ng=?, description=?, sort_order=?, is_enabled=? WHERE defect_type_id=?",
                row.getProductId(), row.getDefectCode(), row.getDefectName(), row.getDefectCategory(),
                row.getDefaultPositionId(), row.getDefaultPositionCode(), row.getDefaultPositionName(),
                row.getDefaultSeverity(), row.getIsNg(), row.getDescription(), row.getSortOrder(), row.getIsEnabled(), id);
    }

    /** 缺陷类型变更后联动相机缺陷映射里的冗余缺陷信息 */
    public int updateMappingDefectInfo(Long defectTypeId, String defectCode, String defectName,
                                        Long positionId, String positionCode, String positionName, Integer isNg) {
        return jdbcTemplate.update(
                "UPDATE cfg_camera_defect_mapping SET defect_code=?, defect_name=?, position_id=?, position_code=?, position_name=?, is_ng=? WHERE defect_type_id=?",
                defectCode, defectName, positionId, positionCode, positionName, isNg, defectTypeId);
    }

    /** 缺陷类型的启用映射数 */
    public int countDefectTypeMappings(Long defectTypeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cfg_camera_defect_mapping WHERE defect_type_id = ? AND is_enabled = 1",
                Integer.class, defectTypeId);
        return count == null ? 0 : count;
    }

    /** 缺陷类型的历史检测事实数 */
    public int countDefectTypeFacts(Long defectTypeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fact_inspection_defect_count WHERE defect_type_id = ?",
                Integer.class, defectTypeId);
        return count == null ? 0 : count;
    }

    /** 逻辑删除缺陷类型 */
    public int softDeleteDefectType(Long id) {
        return jdbcTemplate.update("UPDATE dim_defect_type SET is_deleted = 1, is_enabled = 0 WHERE defect_type_id = ?", id);
    }

    // ==================== 相机缺陷映射 ====================

    /** 相机缺陷映射分页列表 */
    public Map<String, Object> queryMappingPage(Long cameraId, Long productId, String keyword, Integer enabled,
                                                int page, int size) {
        List<Object> params = new ArrayList<>();
        String where = " WHERE 1=1 ";
        if (cameraId != null) { where += " AND m.camera_id = ? "; params.add(cameraId); }
        if (productId != null) { where += " AND m.product_id = ? "; params.add(productId); }
        if (enabled != null) { where += " AND m.is_enabled = ? "; params.add(enabled); }
        if (!isBlank(keyword)) {
            where += " AND (m.camera_code LIKE ? OR m.product_name LIKE ? OR m.source_code LIKE ? OR m.defect_name LIKE ? OR m.position_name LIKE ?) ";
            String like = "%" + keyword.trim() + "%";
            params.add(like); params.add(like); params.add(like); params.add(like); params.add(like);
        }
        return queryPage(
                "SELECT m.mapping_id AS mappingId, m.factory_id AS factoryId, f.factory_name AS factoryName, m.line_id AS lineId, l.line_name AS lineName, " +
                        "m.workstation_id AS workstationId, w.station_name AS workstationName, m.camera_id AS cameraId, c.camera_name AS cameraName, m.camera_code AS cameraCode, " +
                        "m.product_id AS productId, m.product_name AS productName, m.source_code AS sourceCode, m.defect_type_id AS defectTypeId, " +
                        "m.defect_code AS defectCode, m.defect_name AS defectName, m.position_id AS positionId, m.position_code AS positionCode, m.position_name AS positionName, " +
                        "m.severity, m.is_ng AS isNg, m.is_enabled AS isEnabled, m.effective_start AS effectiveStart, m.effective_end AS effectiveEnd, m.update_time AS updateTime " +
                        "FROM cfg_camera_defect_mapping m " +
                        "LEFT JOIN dim_factory f ON m.factory_id = f.factory_id LEFT JOIN dim_production_line l ON m.line_id = l.line_id " +
                        "LEFT JOIN dim_workstation w ON m.workstation_id = w.workstation_id LEFT JOIN dim_camera c ON m.camera_id = c.camera_id " +
                        where + " ORDER BY m.is_enabled DESC, m.update_time DESC, m.mapping_id DESC",
                "SELECT COUNT(*) FROM cfg_camera_defect_mapping m " + where,
                params, page, size);
    }

    /** 按 ID 取相机档案（映射写入前的校验来源） */
    public Map<String, Object> findCameraById(Long cameraId) {
        return one("SELECT camera_id, camera_code, factory_id, line_id, workstation_id FROM dim_camera WHERE camera_id = ? AND is_deleted = 0", cameraId);
    }

    /** 按 ID 取缺陷类型（映射写入前的校验来源） */
    public Map<String, Object> findDefectTypeById(Long defectTypeId) {
        return one("SELECT defect_type_id, defect_code, defect_name, default_position_id, default_position_code, default_position_name, default_severity, is_ng FROM dim_defect_type WHERE defect_type_id = ? AND is_deleted = 0", defectTypeId);
    }

    /** 按 ID 取产品（映射写入前的校验来源） */
    public Map<String, Object> findProductById(Long productId) {
        return one("SELECT product_id, product_name FROM dim_product WHERE product_id = ? AND is_deleted = 0", productId);
    }

    /**
     * 生效期重叠的映射冲突数。
     *
     * <p>SQL 动态片段 {@code AND mapping_id <> ?} 与参数个数严格绑定，
     * 更新时排除自身、新增时不去重 —— 行为与原 Controller 完全一致。
     */
    public int countConflictingMapping(Long cameraId, String sourceCode, Long productId,
                                       Timestamp effectiveEnd, Timestamp effectiveStart, Long mappingId) {
        Integer conflict = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cfg_camera_defect_mapping WHERE camera_id = ? AND source_code = ? " +
                        "AND ((product_id IS NULL AND ? IS NULL) OR product_id = ?) AND is_enabled = 1 " +
                        "AND (? IS NULL OR effective_start < ?) AND (effective_end IS NULL OR effective_end > ?) " +
                        (mappingId == null ? "" : "AND mapping_id <> ?"),
                Integer.class,
                mappingId == null
                        ? new Object[]{cameraId, sourceCode, productId, productId, effectiveEnd, effectiveEnd, effectiveStart}
                        : new Object[]{cameraId, sourceCode, productId, productId, effectiveEnd, effectiveEnd, effectiveStart, mappingId});
        return conflict == null ? 0 : conflict;
    }

    /** 新增相机缺陷映射 */
    public int insertMapping(CameraDefectMappingRow row) {
        return jdbcTemplate.update(
                "INSERT INTO cfg_camera_defect_mapping " +
                        "(factory_id, line_id, workstation_id, camera_id, camera_code, product_id, product_name, source_code, defect_type_id, defect_code, defect_name, " +
                        "position_id, position_code, position_name, severity, is_ng, is_enabled, effective_start, effective_end) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.getFactoryId(), row.getLineId(), row.getWorkstationId(),
                row.getCameraId(), row.getCameraCode(), row.getProductId(), row.getProductName(),
                row.getSourceCode(), row.getDefectTypeId(), row.getDefectCode(), row.getDefectName(),
                row.getPositionId(), row.getPositionCode(), row.getPositionName(),
                row.getSeverity(), row.getIsNg(), row.getIsEnabled(),
                row.getEffectiveStart(), row.getEffectiveEnd());
    }

    /** 更新相机缺陷映射 */
    public int updateMapping(Long mappingId, CameraDefectMappingRow row) {
        return jdbcTemplate.update(
                "UPDATE cfg_camera_defect_mapping SET factory_id=?, line_id=?, workstation_id=?, camera_id=?, camera_code=?, product_id=?, product_name=?, " +
                        "source_code=?, defect_type_id=?, defect_code=?, defect_name=?, position_id=?, position_code=?, position_name=?, severity=?, is_ng=?, is_enabled=?, " +
                        "effective_start=?, effective_end=? WHERE mapping_id=?",
                row.getFactoryId(), row.getLineId(), row.getWorkstationId(),
                row.getCameraId(), row.getCameraCode(), row.getProductId(), row.getProductName(),
                row.getSourceCode(), row.getDefectTypeId(), row.getDefectCode(), row.getDefectName(),
                row.getPositionId(), row.getPositionCode(), row.getPositionName(),
                row.getSeverity(), row.getIsNg(), row.getIsEnabled(),
                row.getEffectiveStart(), row.getEffectiveEnd(), mappingId);
    }

    /** 停用映射（生效结束时间取空则填当前） */
    public int disableMapping(Long mappingId) {
        return jdbcTemplate.update("UPDATE cfg_camera_defect_mapping SET is_enabled = 0, effective_end = COALESCE(effective_end, NOW()) WHERE mapping_id = ?", mappingId);
    }

    // ==================== 映射异常 ====================

    /** 映射异常分页列表 */
    public Map<String, Object> queryMappingExceptionPage(String status, String keyword, int page, int size) {
        List<Object> params = new ArrayList<>();
        String where = " WHERE 1=1 ";
        if (!isBlank(status)) { where += " AND status = ? "; params.add(status.trim()); }
        if (!isBlank(keyword)) {
            where += " AND (camera_code LIKE ? OR product_name LIKE ? OR source_code LIKE ? OR exception_message LIKE ?) ";
            String like = "%" + keyword.trim() + "%";
            params.add(like); params.add(like); params.add(like); params.add(like);
        }
        return queryPage(
                "SELECT exception_id AS exceptionId, batch_id AS batchId, camera_code AS cameraCode, product_name AS productName, source_code AS sourceCode, " +
                        "exception_type AS exceptionType, exception_message AS exceptionMessage, suggestion, status, create_time AS createTime " +
                        "FROM fact_inspection_mapping_exception " + where + " ORDER BY create_time DESC, exception_id DESC",
                "SELECT COUNT(*) FROM fact_inspection_mapping_exception " + where,
                params, page, size);
    }

    /** 关闭映射异常 */
    public int closeMappingException(Long id) {
        return jdbcTemplate.update("UPDATE fact_inspection_mapping_exception SET status = 'CLOSED', handled_time = NOW() WHERE exception_id = ?", id);
    }

    // ==================== 通用 ====================

    /** 分页查询：独立 COUNT + LIMIT ?, ? */
    private Map<String, Object> queryPage(String listSql, String countSql, List<Object> params, int page, int size) {
        int pageNo = Math.max(page, 1);
        int pageSize = Math.max(size, 1);
        Integer total = jdbcTemplate.queryForObject(countSql, Integer.class, params.toArray());
        List<Object> listParams = new ArrayList<>(params);
        listParams.add((pageNo - 1) * pageSize);
        listParams.add(pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(listSql + " LIMIT ?, ?", listParams.toArray());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", rows);
        data.put("total", total == null ? 0 : total);
        data.put("current", pageNo);
        data.put("size", pageSize);
        return data;
    }

    /** 取单行（无结果返回 null） */
    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
