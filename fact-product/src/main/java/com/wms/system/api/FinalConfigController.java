package com.wms.system.api;

import com.wms.shared.common.ResultVO;
import com.wms.system.domain.model.CameraDefectMappingRow;
import com.wms.system.domain.model.DefectTypeConfigRow;
import com.wms.system.domain.model.ProductConfigRow;
import com.wms.system.infra.repository.FinalConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * 最终模型配置接口。
 *
 * P0 覆盖：
 * - 产品管理
 * - 缺陷类型管理
 * - 相机缺陷映射
 * - 映射异常查询
 *
 * <p>DDD P3 批次②：所有 SQL 已下沉到 {@link FinalConfigRepository}，
 * 本类只保留参数校验、字段装配与响应封装。
 */
@RestController
@RequestMapping("/api/v1/config")
public class FinalConfigController {

    @Autowired
    private FinalConfigRepository finalConfigRepository;

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/products")
    public ResultVO<Map<String, Object>> listProducts(@RequestParam(defaultValue = "1") Integer page,
                                                      @RequestParam(defaultValue = "10") Integer size,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) Integer enabled) {
        return ResultVO.success(finalConfigRepository.queryProductPage(keyword, enabled, page, size));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/products")
    public ResultVO<String> createProduct(@RequestBody Map<String, Object> body) {
        try {
            String productCode = required(body, "productCode", "产品编码不能为空");
            String productName = required(body, "productName", "产品名称不能为空");
            int count = finalConfigRepository.countProductByCodeOrName(productCode, productName);
            if (count > 0) return ResultVO.fail("产品编码或名称已存在");

            ProductConfigRow row = new ProductConfigRow();
            row.setProductCode(productCode);
            row.setProductName(productName);
            row.setProductSpec(strOrNull(body.get("productSpec")));
            row.setBrandName(strOrNull(body.get("brandName")));
            row.setDescription(strOrNull(body.get("description")));
            row.setSortOrder(intVal(body.get("sortOrder"), 0));
            row.setIsEnabled(intVal(body.get("isEnabled"), 1));
            finalConfigRepository.insertProduct(row);
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
            int count = finalConfigRepository.countProductByCodeOrNameExcept(id, productCode, productName);
            if (count > 0) return ResultVO.fail("产品编码或名称已存在");

            ProductConfigRow row = new ProductConfigRow();
            row.setProductCode(productCode);
            row.setProductName(productName);
            row.setProductSpec(strOrNull(body.get("productSpec")));
            row.setBrandName(strOrNull(body.get("brandName")));
            row.setDescription(strOrNull(body.get("description")));
            row.setSortOrder(intVal(body.get("sortOrder"), 0));
            row.setIsEnabled(intVal(body.get("isEnabled"), 1));
            finalConfigRepository.updateProduct(id, row);
            finalConfigRepository.updateMappingProductName(id, productName);
            return ResultVO.success("更新成功");
        } catch (IllegalArgumentException e) {
            return ResultVO.fail(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/products/{id}")
    public ResultVO<String> deleteProduct(@PathVariable Long id) {
        int mappingCount = finalConfigRepository.countProductMappings(id);
        int batchCount = finalConfigRepository.countProductInspectionBatches(id);
        if (mappingCount + batchCount > 0) {
            return ResultVO.fail("产品存在映射或检测历史，禁止直接删除。请先停用或完成归档。");
        }
        finalConfigRepository.softDeleteProduct(id);
        return ResultVO.success("删除成功");
    }

    @GetMapping("/defect-types")
    public ResultVO<Map<String, Object>> listDefectTypes(@RequestParam(defaultValue = "1") Integer page,
                                                         @RequestParam(defaultValue = "10") Integer size,
                                                         @RequestParam(required = false) Long productId,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) String severity) {
        return ResultVO.success(finalConfigRepository.queryDefectTypePage(productId, keyword, severity, page, size));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/defect-types")
    public ResultVO<String> createDefectType(@RequestBody Map<String, Object> body) {
        try {
            Long productId = longOrNull(body.get("productId"));
            String defectCode = required(body, "defectCode", "缺陷编码不能为空");
            String defectName = required(body, "defectName", "缺陷名称不能为空");
            int count = finalConfigRepository.countDefectTypeByCode(productId, defectCode);
            if (count > 0) return ResultVO.fail("同一产品下缺陷编码已存在");

            DefectTypeConfigRow row = new DefectTypeConfigRow();
            row.setProductId(productId);
            row.setDefectCode(defectCode);
            row.setDefectName(defectName);
            row.setDefectCategory(strOrNull(body.get("defectCategory")));
            row.setDefaultPositionId(longOrNull(body.get("defaultPositionId")));
            row.setDefaultPositionCode(strOrNull(body.get("defaultPositionCode")));
            row.setDefaultPositionName(strOrNull(body.get("defaultPositionName")));
            row.setDefaultSeverity(defaultSeverity(body.get("defaultSeverity")));
            row.setIsNg(intVal(body.get("isNg"), 1));
            row.setDescription(strOrNull(body.get("description")));
            row.setSortOrder(intVal(body.get("sortOrder"), 0));
            row.setIsEnabled(intVal(body.get("isEnabled"), 1));
            finalConfigRepository.insertDefectType(row);
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
            int count = finalConfigRepository.countDefectTypeByCodeExcept(id, productId, defectCode);
            if (count > 0) return ResultVO.fail("同一产品下缺陷编码已存在");

            DefectTypeConfigRow row = new DefectTypeConfigRow();
            row.setProductId(productId);
            row.setDefectCode(defectCode);
            row.setDefectName(defectName);
            row.setDefectCategory(strOrNull(body.get("defectCategory")));
            row.setDefaultPositionId(longOrNull(body.get("defaultPositionId")));
            row.setDefaultPositionCode(strOrNull(body.get("defaultPositionCode")));
            row.setDefaultPositionName(strOrNull(body.get("defaultPositionName")));
            row.setDefaultSeverity(defaultSeverity(body.get("defaultSeverity")));
            row.setIsNg(intVal(body.get("isNg"), 1));
            row.setDescription(strOrNull(body.get("description")));
            row.setSortOrder(intVal(body.get("sortOrder"), 0));
            row.setIsEnabled(intVal(body.get("isEnabled"), 1));
            finalConfigRepository.updateDefectType(id, row);
            finalConfigRepository.updateMappingDefectInfo(id, defectCode, defectName,
                    row.getDefaultPositionId(), row.getDefaultPositionCode(), row.getDefaultPositionName(), row.getIsNg());
            return ResultVO.success("更新成功");
        } catch (IllegalArgumentException e) {
            return ResultVO.fail(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/defect-types/{id}")
    public ResultVO<String> deleteDefectType(@PathVariable Long id) {
        int mappingCount = finalConfigRepository.countDefectTypeMappings(id);
        int factCount = finalConfigRepository.countDefectTypeFacts(id);
        if (mappingCount + factCount > 0) {
            return ResultVO.fail("缺陷类型存在映射或检测历史，禁止直接删除。请先停用或完成归档。");
        }
        finalConfigRepository.softDeleteDefectType(id);
        return ResultVO.success("删除成功");
    }

    @GetMapping("/camera-defect-mappings")
    public ResultVO<Map<String, Object>> listMappings(@RequestParam(defaultValue = "1") Integer page,
                                                      @RequestParam(defaultValue = "10") Integer size,
                                                      @RequestParam(required = false) Long cameraId,
                                                      @RequestParam(required = false) Long productId,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) Integer enabled) {
        return ResultVO.success(finalConfigRepository.queryMappingPage(cameraId, productId, keyword, enabled, page, size));
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
        finalConfigRepository.disableMapping(id);
        return ResultVO.success("已停用映射");
    }

    @GetMapping("/mapping-exceptions")
    public ResultVO<Map<String, Object>> listMappingExceptions(@RequestParam(defaultValue = "1") Integer page,
                                                              @RequestParam(defaultValue = "10") Integer size,
                                                              @RequestParam(required = false) String status,
                                                              @RequestParam(required = false) String keyword) {
        return ResultVO.success(finalConfigRepository.queryMappingExceptionPage(status, keyword, page, size));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/mapping-exceptions/{id}/close")
    public ResultVO<String> closeException(@PathVariable Long id) {
        finalConfigRepository.closeMappingException(id);
        return ResultVO.success("已关闭");
    }

    private void upsertMapping(Long mappingId, Map<String, Object> body) {
        Long cameraId = requiredLong(body, "cameraId", "相机不能为空");
        Long productId = longOrNull(body.get("productId"));
        Long defectTypeId = requiredLong(body, "defectTypeId", "缺陷类型不能为空");
        String sourceCode = required(body, "sourceCode", "上报编码不能为空");
        LocalDateTime effectiveStart = parseDateTime(strOrNull(body.get("effectiveStart")), LocalDateTime.now());
        LocalDateTime effectiveEnd = parseDateTime(strOrNull(body.get("effectiveEnd")), null);

        Map<String, Object> camera = finalConfigRepository.findCameraById(cameraId);
        if (camera == null) throw new IllegalArgumentException("相机不存在");
        Map<String, Object> defect = finalConfigRepository.findDefectTypeById(defectTypeId);
        if (defect == null) throw new IllegalArgumentException("缺陷类型不存在");
        Map<String, Object> product = productId == null ? null : finalConfigRepository.findProductById(productId);
        if (productId != null && product == null) throw new IllegalArgumentException("产品不存在");

        int conflict = finalConfigRepository.countConflictingMapping(cameraId, sourceCode, productId,
                effectiveEnd == null ? null : Timestamp.valueOf(effectiveEnd),
                Timestamp.valueOf(effectiveStart), mappingId);
        if (conflict > 0) throw new IllegalArgumentException("同一相机、产品、上报编码存在重叠生效映射");

        CameraDefectMappingRow row = new CameraDefectMappingRow();
        row.setFactoryId(longVal(camera.get("factory_id")));
        row.setLineId(longVal(camera.get("line_id")));
        row.setWorkstationId(longVal(camera.get("workstation_id")));
        row.setCameraId(cameraId);
        row.setCameraCode(str(camera.get("camera_code")));
        row.setProductId(productId);
        row.setProductName(product == null ? null : str(product.get("product_name")));
        row.setSourceCode(sourceCode);
        row.setDefectTypeId(defectTypeId);
        row.setDefectCode(str(defect.get("defect_code")));
        row.setDefectName(str(defect.get("defect_name")));
        row.setPositionId(longOrNull(defect.get("default_position_id")));
        row.setPositionCode(strOrNull(defect.get("default_position_code")));
        row.setPositionName(strOrNull(defect.get("default_position_name")));
        row.setSeverity(defaultSeverity(firstNonBlank(strOrNull(body.get("severity")), strOrNull(defect.get("default_severity")))));
        row.setIsNg(intVal(defect.get("is_ng"), 1));
        row.setIsEnabled(intVal(body.get("isEnabled"), 1));
        row.setEffectiveStart(Timestamp.valueOf(effectiveStart));
        row.setEffectiveEnd(effectiveEnd == null ? null : Timestamp.valueOf(effectiveEnd));

        if (mappingId == null) {
            finalConfigRepository.insertMapping(row);
        } else {
            finalConfigRepository.updateMapping(mappingId, row);
        }
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
