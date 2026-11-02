package com.wms.controller;

import com.wms.vo.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 最终模型配置接口。
 *
 * P0 覆盖：
 * - 产品管理
 * - 缺陷类型管理
 * - 相机缺陷映射
 * - 映射异常查询
 */
@RestController
@RequestMapping("/api/v1/config")
public class FinalConfigController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/products")
    public ResultVO<Map<String, Object>> listProducts(@RequestParam(defaultValue = "1") Integer page,
                                                      @RequestParam(defaultValue = "10") Integer size,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) Integer enabled) {
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
        return ResultVO.success(queryPage(
                "SELECT product_id AS productId, product_code AS productCode, product_name AS productName, product_spec AS productSpec, " +
                        "brand_name AS brandName, description, sort_order AS sortOrder, is_enabled AS isEnabled, create_time AS createTime, update_time AS updateTime " +
                        "FROM dim_product " + where + " ORDER BY sort_order ASC, product_id DESC",
                "SELECT COUNT(*) FROM dim_product " + where,
                params, page, size));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/products")
    public ResultVO<String> createProduct(@RequestBody Map<String, Object> body) {
        try {
            String productCode = required(body, "productCode", "产品编码不能为空");
            String productName = required(body, "productName", "产品名称不能为空");
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dim_product WHERE is_deleted = 0 AND (product_code = ? OR product_name = ?)",
                    Integer.class, productCode, productName);
            if (count != null && count > 0) return ResultVO.fail("产品编码或名称已存在");
            jdbcTemplate.update(
                    "INSERT INTO dim_product (product_code, product_name, product_spec, brand_name, description, sort_order, is_enabled) VALUES (?,?,?,?,?,?,?)",
                    productCode, productName, strOrNull(body.get("productSpec")), strOrNull(body.get("brandName")),
                    strOrNull(body.get("description")), intVal(body.get("sortOrder"), 0), intVal(body.get("isEnabled"), 1));
            return ResultVO.success("创建成功");
        } catch (IllegalArgumentException e) {
            return ResultVO.fail(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/products/{id}")
    @Transactional
    public ResultVO<String> updateProduct(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String productCode = required(body, "productCode", "产品编码不能为空");
            String productName = required(body, "productName", "产品名称不能为空");
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dim_product WHERE is_deleted = 0 AND product_id <> ? AND (product_code = ? OR product_name = ?)",
                    Integer.class, id, productCode, productName);
            if (count != null && count > 0) return ResultVO.fail("产品编码或名称已存在");
            jdbcTemplate.update(
                    "UPDATE dim_product SET product_code=?, product_name=?, product_spec=?, brand_name=?, description=?, sort_order=?, is_enabled=? WHERE product_id=?",
                    productCode, productName, strOrNull(body.get("productSpec")), strOrNull(body.get("brandName")),
                    strOrNull(body.get("description")), intVal(body.get("sortOrder"), 0), intVal(body.get("isEnabled"), 1), id);
            jdbcTemplate.update("UPDATE cfg_camera_defect_mapping SET product_name=? WHERE product_id=?",
                    productName, id);
            return ResultVO.success("更新成功");
        } catch (IllegalArgumentException e) {
            return ResultVO.fail(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/products/{id}")
    public ResultVO<String> deleteProduct(@PathVariable Long id) {
        Integer mappingCount = count("SELECT COUNT(*) FROM cfg_camera_defect_mapping WHERE product_id = ? AND is_enabled = 1", id);
        Integer batchCount = count("SELECT COUNT(*) FROM fact_inspection_batch WHERE product_id = ?", id);
        if (mappingCount + batchCount > 0) {
            return ResultVO.fail("产品存在映射或检测历史，禁止直接删除。请先停用或完成归档。");
        }
        jdbcTemplate.update("UPDATE dim_product SET is_deleted = 1, is_enabled = 0 WHERE product_id = ?", id);
        return ResultVO.success("删除成功");
    }

    @GetMapping("/defect-types")
    public ResultVO<Map<String, Object>> listDefectTypes(@RequestParam(defaultValue = "1") Integer page,
                                                         @RequestParam(defaultValue = "10") Integer size,
                                                         @RequestParam(required = false) Long productId,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) String severity) {
        List<Object> params = new ArrayList<>();
        String where = " WHERE dt.is_deleted = 0 ";
        if (productId != null) { where += " AND dt.product_id = ? "; params.add(productId); }
        if (!isBlank(keyword)) {
            where += " AND (dt.defect_code LIKE ? OR dt.defect_name LIKE ? OR p.product_name LIKE ?) ";
            String like = "%" + keyword.trim() + "%";
            params.add(like); params.add(like); params.add(like);
        }
        if (!isBlank(severity)) { where += " AND dt.default_severity = ? "; params.add(severity.trim()); }
        return ResultVO.success(queryPage(
                "SELECT dt.defect_type_id AS defectTypeId, dt.product_id AS productId, p.product_name AS productName, " +
                        "dt.defect_code AS defectCode, dt.defect_name AS defectName, dt.defect_category AS defectCategory, " +
                        "dt.default_position_id AS defaultPositionId, dt.default_position_code AS defaultPositionCode, dt.default_position_name AS defaultPositionName, " +
                        "dt.default_severity AS defaultSeverity, dt.is_ng AS isNg, dt.description, dt.sort_order AS sortOrder, dt.is_enabled AS isEnabled, dt.update_time AS updateTime " +
                        "FROM dim_defect_type dt LEFT JOIN dim_product p ON dt.product_id = p.product_id " + where +
                        " ORDER BY COALESCE(p.sort_order,0), dt.sort_order ASC, dt.defect_type_id DESC",
                "SELECT COUNT(*) FROM dim_defect_type dt LEFT JOIN dim_product p ON dt.product_id = p.product_id " + where,
                params, page, size));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/defect-types")
    public ResultVO<String> createDefectType(@RequestBody Map<String, Object> body) {
        try {
            Long productId = longOrNull(body.get("productId"));
            String defectCode = required(body, "defectCode", "缺陷编码不能为空");
            String defectName = required(body, "defectName", "缺陷名称不能为空");
            Integer count = jdbcTemplate.queryForObject(
                    productId == null
                            ? "SELECT COUNT(*) FROM dim_defect_type WHERE is_deleted = 0 AND product_id IS NULL AND defect_code = ?"
                            : "SELECT COUNT(*) FROM dim_defect_type WHERE is_deleted = 0 AND product_id = ? AND defect_code = ?",
                    Integer.class,
                    productId == null ? new Object[]{defectCode} : new Object[]{productId, defectCode});
            if (count != null && count > 0) return ResultVO.fail("同一产品下缺陷编码已存在");
            jdbcTemplate.update(
                    "INSERT INTO dim_defect_type (product_id, defect_code, defect_name, defect_category, default_position_id, default_position_code, default_position_name, " +
                            "default_severity, is_ng, description, sort_order, is_enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    productId, defectCode, defectName, strOrNull(body.get("defectCategory")), longOrNull(body.get("defaultPositionId")),
                    strOrNull(body.get("defaultPositionCode")), strOrNull(body.get("defaultPositionName")),
                    defaultSeverity(body.get("defaultSeverity")), intVal(body.get("isNg"), 1), strOrNull(body.get("description")),
                    intVal(body.get("sortOrder"), 0), intVal(body.get("isEnabled"), 1));
            return ResultVO.success("创建成功");
        } catch (IllegalArgumentException e) {
            return ResultVO.fail(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/defect-types/{id}")
    @Transactional
    public ResultVO<String> updateDefectType(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            Long productId = longOrNull(body.get("productId"));
            String defectCode = required(body, "defectCode", "缺陷编码不能为空");
            String defectName = required(body, "defectName", "缺陷名称不能为空");
            Integer count = jdbcTemplate.queryForObject(
                    productId == null
                            ? "SELECT COUNT(*) FROM dim_defect_type WHERE is_deleted = 0 AND product_id IS NULL AND defect_code = ? AND defect_type_id <> ?"
                            : "SELECT COUNT(*) FROM dim_defect_type WHERE is_deleted = 0 AND product_id = ? AND defect_code = ? AND defect_type_id <> ?",
                    Integer.class,
                    productId == null ? new Object[]{defectCode, id} : new Object[]{productId, defectCode, id});
            if (count != null && count > 0) return ResultVO.fail("同一产品下缺陷编码已存在");
            jdbcTemplate.update(
                    "UPDATE dim_defect_type SET product_id=?, defect_code=?, defect_name=?, defect_category=?, default_position_id=?, default_position_code=?, " +
                            "default_position_name=?, default_severity=?, is_ng=?, description=?, sort_order=?, is_enabled=? WHERE defect_type_id=?",
                    productId, defectCode, defectName, strOrNull(body.get("defectCategory")), longOrNull(body.get("defaultPositionId")),
                    strOrNull(body.get("defaultPositionCode")), strOrNull(body.get("defaultPositionName")),
                    defaultSeverity(body.get("defaultSeverity")), intVal(body.get("isNg"), 1), strOrNull(body.get("description")),
                    intVal(body.get("sortOrder"), 0), intVal(body.get("isEnabled"), 1), id);
            jdbcTemplate.update(
                    "UPDATE cfg_camera_defect_mapping SET defect_code=?, defect_name=?, position_id=?, position_code=?, position_name=?, is_ng=? WHERE defect_type_id=?",
                    defectCode, defectName, longOrNull(body.get("defaultPositionId")), strOrNull(body.get("defaultPositionCode")),
                    strOrNull(body.get("defaultPositionName")), intVal(body.get("isNg"), 1), id);
            return ResultVO.success("更新成功");
        } catch (IllegalArgumentException e) {
            return ResultVO.fail(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/defect-types/{id}")
    public ResultVO<String> deleteDefectType(@PathVariable Long id) {
        Integer mappingCount = count("SELECT COUNT(*) FROM cfg_camera_defect_mapping WHERE defect_type_id = ? AND is_enabled = 1", id);
        Integer factCount = count("SELECT COUNT(*) FROM fact_inspection_defect_count WHERE defect_type_id = ?", id);
        if (mappingCount + factCount > 0) {
            return ResultVO.fail("缺陷类型存在映射或检测历史，禁止直接删除。请先停用或完成归档。");
        }
        jdbcTemplate.update("UPDATE dim_defect_type SET is_deleted = 1, is_enabled = 0 WHERE defect_type_id = ?", id);
        return ResultVO.success("删除成功");
    }

    @GetMapping("/camera-defect-mappings")
    public ResultVO<Map<String, Object>> listMappings(@RequestParam(defaultValue = "1") Integer page,
                                                      @RequestParam(defaultValue = "10") Integer size,
                                                      @RequestParam(required = false) Long cameraId,
                                                      @RequestParam(required = false) Long productId,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) Integer enabled) {
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
        return ResultVO.success(queryPage(
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
                params, page, size));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/camera-defect-mappings")
    public ResultVO<String> createMapping(@RequestBody Map<String, Object> body) {
        try {
            upsertMapping(null, body);
            return ResultVO.success("创建成功");
        } catch (IllegalArgumentException e) {
            return ResultVO.fail(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/camera-defect-mappings/{id}")
    @Transactional
    public ResultVO<String> updateMapping(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            upsertMapping(id, body);
            return ResultVO.success("更新成功");
        } catch (IllegalArgumentException e) {
            return ResultVO.fail(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/camera-defect-mappings/{id}")
    public ResultVO<String> disableMapping(@PathVariable Long id) {
        jdbcTemplate.update("UPDATE cfg_camera_defect_mapping SET is_enabled = 0, effective_end = COALESCE(effective_end, NOW()) WHERE mapping_id = ?", id);
        return ResultVO.success("已停用映射");
    }

    @GetMapping("/mapping-exceptions")
    public ResultVO<Map<String, Object>> listMappingExceptions(@RequestParam(defaultValue = "1") Integer page,
                                                              @RequestParam(defaultValue = "10") Integer size,
                                                              @RequestParam(required = false) String status,
                                                              @RequestParam(required = false) String keyword) {
        List<Object> params = new ArrayList<>();
        String where = " WHERE 1=1 ";
        if (!isBlank(status)) { where += " AND status = ? "; params.add(status.trim()); }
        if (!isBlank(keyword)) {
            where += " AND (camera_code LIKE ? OR product_name LIKE ? OR source_code LIKE ? OR exception_message LIKE ?) ";
            String like = "%" + keyword.trim() + "%";
            params.add(like); params.add(like); params.add(like); params.add(like);
        }
        return ResultVO.success(queryPage(
                "SELECT exception_id AS exceptionId, batch_id AS batchId, camera_code AS cameraCode, product_name AS productName, source_code AS sourceCode, " +
                        "exception_type AS exceptionType, exception_message AS exceptionMessage, suggestion, status, create_time AS createTime " +
                        "FROM fact_inspection_mapping_exception " + where + " ORDER BY create_time DESC, exception_id DESC",
                "SELECT COUNT(*) FROM fact_inspection_mapping_exception " + where,
                params, page, size));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/mapping-exceptions/{id}/close")
    public ResultVO<String> closeException(@PathVariable Long id) {
        jdbcTemplate.update("UPDATE fact_inspection_mapping_exception SET status = 'CLOSED', handled_time = NOW() WHERE exception_id = ?", id);
        return ResultVO.success("已关闭");
    }

    private void upsertMapping(Long mappingId, Map<String, Object> body) {
        Long cameraId = requiredLong(body, "cameraId", "相机不能为空");
        Long productId = longOrNull(body.get("productId"));
        Long defectTypeId = requiredLong(body, "defectTypeId", "缺陷类型不能为空");
        String sourceCode = required(body, "sourceCode", "上报编码不能为空");
        LocalDateTime effectiveStart = parseDateTime(strOrNull(body.get("effectiveStart")), LocalDateTime.now());
        LocalDateTime effectiveEnd = parseDateTime(strOrNull(body.get("effectiveEnd")), null);

        Map<String, Object> camera = one("SELECT camera_id, camera_code, factory_id, line_id, workstation_id FROM dim_camera WHERE camera_id = ? AND is_deleted = 0", cameraId);
        if (camera == null) throw new IllegalArgumentException("相机不存在");
        Map<String, Object> defect = one("SELECT defect_type_id, defect_code, defect_name, default_position_id, default_position_code, default_position_name, default_severity, is_ng FROM dim_defect_type WHERE defect_type_id = ? AND is_deleted = 0", defectTypeId);
        if (defect == null) throw new IllegalArgumentException("缺陷类型不存在");
        Map<String, Object> product = productId == null ? null : one("SELECT product_id, product_name FROM dim_product WHERE product_id = ? AND is_deleted = 0", productId);
        if (productId != null && product == null) throw new IllegalArgumentException("产品不存在");

        Integer conflict = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cfg_camera_defect_mapping WHERE camera_id = ? AND source_code = ? " +
                        "AND ((product_id IS NULL AND ? IS NULL) OR product_id = ?) AND is_enabled = 1 " +
                        "AND (? IS NULL OR effective_start < ?) AND (effective_end IS NULL OR effective_end > ?) " +
                        (mappingId == null ? "" : "AND mapping_id <> ?"),
                Integer.class,
                mappingId == null
                        ? new Object[]{cameraId, sourceCode, productId, productId, effectiveEnd == null ? null : Timestamp.valueOf(effectiveEnd), effectiveEnd == null ? null : Timestamp.valueOf(effectiveEnd), Timestamp.valueOf(effectiveStart)}
                        : new Object[]{cameraId, sourceCode, productId, productId, effectiveEnd == null ? null : Timestamp.valueOf(effectiveEnd), effectiveEnd == null ? null : Timestamp.valueOf(effectiveEnd), Timestamp.valueOf(effectiveStart), mappingId});
        if (conflict != null && conflict > 0) throw new IllegalArgumentException("同一相机、产品、上报编码存在重叠生效映射");

        if (mappingId == null) {
            jdbcTemplate.update(
                    "INSERT INTO cfg_camera_defect_mapping " +
                            "(factory_id, line_id, workstation_id, camera_id, camera_code, product_id, product_name, source_code, defect_type_id, defect_code, defect_name, " +
                            "position_id, position_code, position_name, severity, is_ng, is_enabled, effective_start, effective_end) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    longVal(camera.get("factory_id")), longVal(camera.get("line_id")), longVal(camera.get("workstation_id")),
                    cameraId, str(camera.get("camera_code")), productId, product == null ? null : str(product.get("product_name")),
                    sourceCode, defectTypeId, str(defect.get("defect_code")), str(defect.get("defect_name")),
                    longOrNull(defect.get("default_position_id")), strOrNull(defect.get("default_position_code")), strOrNull(defect.get("default_position_name")),
                    defaultSeverity(firstNonBlank(strOrNull(body.get("severity")), strOrNull(defect.get("default_severity")))),
                    intVal(defect.get("is_ng"), 1), intVal(body.get("isEnabled"), 1), Timestamp.valueOf(effectiveStart),
                    effectiveEnd == null ? null : Timestamp.valueOf(effectiveEnd));
        } else {
            jdbcTemplate.update(
                    "UPDATE cfg_camera_defect_mapping SET factory_id=?, line_id=?, workstation_id=?, camera_id=?, camera_code=?, product_id=?, product_name=?, " +
                            "source_code=?, defect_type_id=?, defect_code=?, defect_name=?, position_id=?, position_code=?, position_name=?, severity=?, is_ng=?, is_enabled=?, " +
                            "effective_start=?, effective_end=? WHERE mapping_id=?",
                    longVal(camera.get("factory_id")), longVal(camera.get("line_id")), longVal(camera.get("workstation_id")),
                    cameraId, str(camera.get("camera_code")), productId, product == null ? null : str(product.get("product_name")),
                    sourceCode, defectTypeId, str(defect.get("defect_code")), str(defect.get("defect_name")),
                    longOrNull(defect.get("default_position_id")), strOrNull(defect.get("default_position_code")), strOrNull(defect.get("default_position_name")),
                    defaultSeverity(firstNonBlank(strOrNull(body.get("severity")), strOrNull(defect.get("default_severity")))),
                    intVal(defect.get("is_ng"), 1), intVal(body.get("isEnabled"), 1), Timestamp.valueOf(effectiveStart),
                    effectiveEnd == null ? null : Timestamp.valueOf(effectiveEnd), mappingId);
        }
    }

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

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Integer count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String required(Map<String, Object> body, String key, String message) {
        String value = strOrNull(body.get(key));
        if (isBlank(value)) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private Long requiredLong(Map<String, Object> body, String key, String message) {
        Long value = longOrNull(body.get(key));
        if (value == null || value <= 0) throw new IllegalArgumentException(message);
        return value;
    }

    private LocalDateTime parseDateTime(String value, LocalDateTime defaultValue) {
        if (isBlank(value)) return defaultValue;
        try { return LocalDateTime.parse(value.trim(), DT); }
        catch (Exception e) { return LocalDateTime.parse(value.trim()); }
    }

    private String defaultSeverity(Object value) {
        String s = strOrNull(value);
        if (isBlank(s)) return "MEDIUM";
        String u = s.trim().toUpperCase(Locale.ROOT);
        return Arrays.asList("HIGH", "MEDIUM", "LOW").contains(u) ? u : "MEDIUM";
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private String strOrNull(Object o) { return o == null ? null : String.valueOf(o); }
    private String firstNonBlank(String a, String b) { return !isBlank(a) ? a : b; }
    private int intVal(Object o, int defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Number) return ((Number) o).intValue();
        String text = String.valueOf(o);
        return text.isEmpty() ? defaultValue : Integer.parseInt(text);
    }
    private Long longVal(Object o) {
        Long v = longOrNull(o);
        return v == null ? 0L : v;
    }
    private Long longOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        String text = String.valueOf(o).trim();
        return text.isEmpty() ? null : Long.parseLong(text);
    }
}
