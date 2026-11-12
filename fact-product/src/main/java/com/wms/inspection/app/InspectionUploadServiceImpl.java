package com.wms.inspection.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.inspection.api.dto.InspectionBatchUploadRequest;
import com.wms.inspection.api.dto.InspectionBatchUploadResponse;
import com.wms.inspection.app.InspectionUploadException;
import com.wms.inspection.app.InspectionUploadService;
import com.wms.inspection.infra.repository.InspectionUploadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 新版检测上报 P0 实现。
 *
 * 设计原则：
 * - 只接收 INCREMENTAL 增量计数；
 * - 产品、相机、缺陷映射均必须配置后才能入库；
 * - 未映射编码返回 HTTP 422，同时写入 fact_inspection_mapping_exception；
 * - P0 同步写入 30 分钟聚合表，保障产线统计可立即查询。
 */
@Slf4j
@Service
public class InspectionUploadServiceImpl implements InspectionUploadService {

    /** DDD P3 批次⑥：SQL 已下沉到 InspectionUploadRepository */
    @Autowired
    private InspectionUploadRepository inspectionUploadRepository;

    /**
     * 严格模式：未配置令牌的相机是否拒绝上报。
     *
     * <p>与 {@code com.wms.device.app.CameraTokenVerifier} 读同一个 key、同一套语义
     * （两处都改了才算改全）。默认 false 的原因见该类注释：现场大量相机未配 token，
     * 一律拒绝会直接停线。
     */
    @Value("${camera.token.strict:false}")
    private boolean tokenStrictMode;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = InspectionUploadException.class)
    public InspectionBatchUploadResponse upload(InspectionBatchUploadRequest request,
                                                String authorization,
                                                String deviceIdHeader,
                                                String batchIdHeader) {
        InspectionBatchUploadResponse response = new InspectionBatchUploadResponse();

        validateBasic(request, authorization, deviceIdHeader, batchIdHeader, response);

        String token = authorization.substring("Bearer ".length()).trim();
        String batchId = firstNonBlank(request.getBatchId(), batchIdHeader);
        String cameraCode = trim(request.getCameraCode());
        String productName = trim(request.getProductName());
        LocalDateTime reportTime = parseDateTime(request.getReportTime(), "reportTime");
        LocalDateTime startTime = parseDateTime(request.getTimeRange().getStartTime(), "timeRange.startTime");
        LocalDateTime endTime = parseDateTime(request.getTimeRange().getEndTime(), "timeRange.endTime");
        LocalDateTime bucket30m = floorToBucket(reportTime, 30);
        LocalDateTime bucket10m = floorToBucket(reportTime, 10);

        Map<String, Object> camera = findCamera(cameraCode, deviceIdHeader);
        if (camera == null) {
            fail422(response, "CAMERA_NOT_FOUND", null,
                    "未找到相机配置：" + cameraCode,
                    "请先在系统配置中维护相机，并确认 cameraCode / X-Device-Id 与设备一致。");
        }
        Long cameraId = longVal(camera.get("camera_id"));
        String normalizedCameraCode = str(camera.get("camera_code"));
        Long factoryId = longVal(camera.get("factory_id"));
        Long lineId = longVal(camera.get("line_id"));
        Long workstationId = longVal(camera.get("workstation_id"));

        response.setBatchId(batchId);
        response.setCameraId(cameraId);
        response.setCameraCode(normalizedCameraCode);

        validateToken(cameraId, normalizedCameraCode, token, response);

        Map<String, Object> product = findProduct(productName);
        if (product == null) {
            recordException(batchId, request.getRequestId(), cameraId, normalizedCameraCode, null, productName,
                    null, "PRODUCT_NOT_FOUND",
                    "产品未配置或已停用：" + productName,
                    "请在系统配置 > 产品管理中维护产品名称，设备上报 productName 必须完全匹配。",
                    factoryId, lineId, workstationId, reportTime);
            fail422(response, "PRODUCT_NOT_FOUND", null,
                    "产品未配置或已停用：" + productName,
                    "请先维护产品，并确认 productName 字段与配置完全一致。");
        }
        Long productId = longVal(product.get("product_id"));
        String normalizedProductName = str(product.get("product_name"));
        response.setProductId(productId);
        response.setProductName(normalizedProductName);

        long total = nvl(request.getSummary().getTotalCount());
        long ok = nvl(request.getSummary().getOkCount());
        long ng = nvl(request.getSummary().getNgCount());
        long defectSum = request.getDefects().stream().mapToLong(d -> nvl(d.getCount())).sum();

        response.setTotalCount(total);
        response.setOkCount(ok);
        response.setNgCount(ng);
        response.setTimeBucket30m(bucket30m.format(DT));

        if (total != ok + ng) {
            recordException(batchId, request.getRequestId(), cameraId, normalizedCameraCode, productId, normalizedProductName,
                    null, "COUNT_MISMATCH",
                    "汇总计数不一致：totalCount 必须等于 okCount + ngCount。",
                    "请检查设备端计数逻辑，本批次未入库。",
                    factoryId, lineId, workstationId, reportTime);
            fail422(response, "COUNT_MISMATCH", null,
                    "计数不一致：totalCount 必须等于 okCount + ngCount。",
                    "请检查 summary.totalCount / okCount / ngCount。");
        }
        if (ng != defectSum) {
            recordException(batchId, request.getRequestId(), cameraId, normalizedCameraCode, productId, normalizedProductName,
                    null, "COUNT_MISMATCH",
                    "缺陷计数不一致：ngCount 必须等于 defects[].count 之和。",
                    "请检查设备端 defects 数组，确保每个缺陷项都按增量上报。",
                    factoryId, lineId, workstationId, reportTime);
            fail422(response, "COUNT_MISMATCH", null,
                    "计数不一致：ngCount 必须等于 defects[].count 之和。",
                    "请检查 defects[].count。");
        }

        Integer existed = inspectionUploadRepository.countExistingBatch(cameraId, batchId);
        if (existed != null && existed > 0) {
            response.setAccepted(true);
            response.setDuplicate(true);
            response.setInspectionBatchId(queryExistingBatchId(cameraId, batchId));
            return response;
        }

        List<Map<String, Object>> mappedRows = new ArrayList<>();
        for (InspectionBatchUploadRequest.DefectItem item : request.getDefects()) {
            String sourceCode = trim(item.getSourceCode());
            long count = nvl(item.getCount());
            if (count < 0) {
                fail422(response, "INVALID_DEFECT_COUNT", sourceCode,
                        "缺陷数量不能为负数：" + sourceCode,
                        "请检查设备端 defects[].count。");
            }
            Map<String, Object> mapping = findMapping(cameraId, productId, sourceCode, reportTime);
            if (mapping == null) {
                recordException(batchId, request.getRequestId(), cameraId, normalizedCameraCode, productId, normalizedProductName,
                        sourceCode, "UNMAPPED",
                        "上报编码未配置映射：" + sourceCode,
                        "请在系统配置 > 相机缺陷映射中配置 产品 + 相机 + " + sourceCode + " 到标准缺陷类型的一对一映射。",
                        factoryId, lineId, workstationId, reportTime);
                fail422(response, "UNMAPPED", sourceCode,
                        "上报编码未配置映射：" + sourceCode,
                        "请先维护相机缺陷映射，确保每个 sourceCode 均能一对一匹配。");
            }
            mapping.put("_source_code", sourceCode);
            mapping.put("_count", count);
            mappedRows.add(mapping);
        }

        Long shiftId = resolveShiftId(reportTime);
        String rawJson = toJson(request);
        Long inspectionBatchId = insertBatch(batchId, request.getRequestId(), request.getSchemaVersion(),
                cameraId, normalizedCameraCode, str(camera.get("camera_name")),
                productId, normalizedProductName,
                factoryId, lineId, workstationId, shiftId,
                reportTime, startTime, endTime,
                total, ok, ng, defectSum, rawJson);
        response.setInspectionBatchId(inspectionBatchId);

        for (Map<String, Object> mapping : mappedRows) {
            insertDefectCount(inspectionBatchId, batchId, cameraId, normalizedCameraCode, productId, normalizedProductName,
                    factoryId, lineId, workstationId, shiftId, reportTime, bucket10m, bucket30m, mapping);
            upsertDefectAgg(bucket30m, factoryId, lineId, workstationId, cameraId, productId, shiftId, mapping);

            InspectionBatchUploadResponse.MappedDefect item = new InspectionBatchUploadResponse.MappedDefect();
            item.setSourceCode(str(mapping.get("_source_code")));
            item.setMappingId(longVal(mapping.get("mapping_id")));
            item.setDefectTypeId(longVal(mapping.get("defect_type_id")));
            item.setDefectCode(str(mapping.get("defect_code")));
            item.setDefectName(str(mapping.get("defect_name")));
            item.setSeverity(str(mapping.get("severity")));
            item.setCount(longVal(mapping.get("_count")));
            response.getMappedDefects().add(item);
        }

        upsertQualityAgg(bucket30m, factoryId, lineId, workstationId, cameraId, productId, shiftId,
                total, ok, ng, defectSum);
        upsertCurrentStatus(cameraId, normalizedCameraCode, productId, normalizedProductName, reportTime,
                total, ok, ng);
        syncLegacyCameraStatus(normalizedCameraCode, normalizedProductName, reportTime, total, ok, ng);

        response.setAccepted(true);
        response.setDuplicate(false);
        response.setMappedDefectCount(defectSum);
        response.setUnmappedDefectCount(0L);
        return response;
    }

    private void validateBasic(InspectionBatchUploadRequest req,
                               String authorization,
                               String deviceIdHeader,
                               String batchIdHeader,
                               InspectionBatchUploadResponse response) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.substring(7).trim().isEmpty()) {
            fail422(response, "TOKEN_REQUIRED", null, "缺少设备上报 Token。", "请求头必须包含 Authorization: Bearer <device-token>。");
        }
        if (isBlank(deviceIdHeader)) {
            fail422(response, "DEVICE_ID_REQUIRED", null, "缺少设备编号。", "请求头必须包含 X-Device-Id。");
        }
        if (req == null) {
            fail422(response, "INVALID_BODY", null, "请求体不能为空。", "请按新版 JSON 协议提交检测批次数据。");
        }
        if (!"2.0".equals(trim(req.getSchemaVersion()))) {
            fail422(response, "INVALID_SCHEMA_VERSION", null, "schemaVersion 必须为 2.0。", "请升级设备端上报协议。");
        }
        if (isBlank(firstNonBlank(req.getBatchId(), batchIdHeader))) {
            fail422(response, "BATCH_ID_REQUIRED", null, "缺少 batchId。", "请求体 batchId 或请求头 X-Batch-Id 必须填写。");
        }
        if (isBlank(req.getCameraCode())) {
            fail422(response, "CAMERA_CODE_REQUIRED", null, "缺少 cameraCode。", "请填写相机编号。");
        }
        if (isBlank(req.getProductName())) {
            fail422(response, "PRODUCT_NAME_REQUIRED", null, "缺少 productName。", "请填写当前检测产品名称。");
        }
        if (!"INCREMENTAL".equalsIgnoreCase(trim(req.getCounterMode()))) {
            fail422(response, "UNSUPPORTED_COUNTER_MODE", null, "P0 只支持 INCREMENTAL 增量计数。", "请将 counterMode 固定为 INCREMENTAL。");
        }
        if (req.getSummary() == null) {
            fail422(response, "SUMMARY_REQUIRED", null, "缺少 summary。", "请填写 totalCount / okCount / ngCount。");
        }
        if (req.getTimeRange() == null || isBlank(req.getTimeRange().getStartTime()) || isBlank(req.getTimeRange().getEndTime())) {
            fail422(response, "TIME_RANGE_REQUIRED", null, "缺少 timeRange。", "请填写 startTime / endTime。");
        }
        if (req.getDefects() == null) req.setDefects(new ArrayList<>());
    }

    private void validateToken(Long cameraId, String cameraCode, String token, InspectionBatchUploadResponse response) {
        List<Map<String, Object>> rows;
        try {
            rows = inspectionUploadRepository.listCameraTokens(cameraId);
        } catch (Exception e) {
            // 兼容开发环境：未初始化 token 表时仍要求有 Bearer Token，但不阻断联调。
            log.warn("cfg_camera_token 未就绪，仅校验 Token 必填。cameraCode={}", cameraCode);
            return;
        }
        if (rows == null || rows.isEmpty()) {
            // ★ 2026-11-12 修复：原实现直接 return —— 该相机未配令牌即完全跳过校验，
            //   与「令牌无效即 422」的契约相反。现按 camera.token.strict 决定：
            //   严格模式拒绝，兼容模式放行但留 WARN（让「哪些相机还没配令牌」可见）。
            if (tokenStrictMode) {
                fail422(response, "TOKEN_NOT_CONFIGURED", null,
                        "该相机未配置设备令牌，已按严格模式拒绝。",
                        "请先在设备令牌页为该相机生成令牌，再重新上报。");
            } else {
                log.warn("相机未配置设备令牌，按兼容模式放行: cameraCode={} —— "
                        + "配置后建议开启 camera.token.strict 收紧", cameraCode);
            }
            return;
        }
        String sha256 = sha256(token);
        for (Map<String, Object> row : rows) {
            String configured = str(row.get("token_hash"));
            if (configured.equals(token) || configured.equalsIgnoreCase(sha256)) return;
        }
        fail422(response, "TOKEN_INVALID", null, "设备 Token 无效或已过期。", "请检查设备 Token 配置，必要时重新生成。");
    }

    private Map<String, Object> findCamera(String cameraCode, String deviceIdHeader) {
        return inspectionUploadRepository.findCamera(cameraCode, deviceIdHeader);
    }

    private Map<String, Object> findProduct(String productName) {
        return inspectionUploadRepository.findProduct(productName);
    }

    private Map<String, Object> findMapping(Long cameraId, Long productId, String sourceCode, LocalDateTime reportTime) {
        return inspectionUploadRepository.findMapping(cameraId, productId, sourceCode, reportTime);
    }

    private Long insertBatch(String batchId, String requestId, String schemaVersion,
                             Long cameraId, String cameraCode, String cameraName,
                             Long productId, String productName,
                             Long factoryId, Long lineId, Long workstationId, Long shiftId,
                             LocalDateTime reportTime, LocalDateTime startTime, LocalDateTime endTime,
                             long total, long ok, long ng, long mappedDefectCount, String rawJson) {
        // DDD P3 批次⑥：INSERT 已下沉到 InspectionUploadRepository
        return inspectionUploadRepository.insertBatch(batchId, requestId, schemaVersion,
                cameraId, cameraCode, cameraName, productId, productName,
                factoryId, lineId, workstationId, shiftId,
                reportTime, startTime, endTime, total, ok, ng, mappedDefectCount, rawJson);
    }

    private void insertDefectCount(Long inspectionBatchId, String batchId, Long cameraId, String cameraCode,
                                   Long productId, String productName, Long factoryId, Long lineId, Long workstationId,
                                   Long shiftId, LocalDateTime reportTime, LocalDateTime bucket10m, LocalDateTime bucket30m,
                                   Map<String, Object> mapping) {
        inspectionUploadRepository.insertDefectCount(inspectionBatchId, batchId, cameraId, cameraCode,
                productId, productName, factoryId, lineId, workstationId, shiftId,
                reportTime, bucket10m, bucket30m, mapping);
    }

    private void upsertQualityAgg(LocalDateTime bucket, Long factoryId, Long lineId, Long workstationId,
                                  Long cameraId, Long productId, Long shiftId,
                                  long total, long ok, long ng, long defect) {
        inspectionUploadRepository.upsertQualityAgg(bucket, factoryId, lineId, workstationId,
                cameraId, productId, shiftId, total, ok, ng, defect);
    }

    private void upsertDefectAgg(LocalDateTime bucket, Long factoryId, Long lineId, Long workstationId,
                                 Long cameraId, Long productId, Long shiftId, Map<String, Object> mapping) {
        inspectionUploadRepository.upsertDefectAgg(bucket, factoryId, lineId, workstationId,
                cameraId, productId, shiftId, mapping);
    }

    private void upsertCurrentStatus(Long cameraId, String cameraCode, Long productId, String productName,
                                     LocalDateTime reportTime, long total, long ok, long ng) {
        inspectionUploadRepository.upsertCurrentStatus(cameraId, cameraCode, productId, productName,
                reportTime, total, ok, ng);
    }

    private void syncLegacyCameraStatus(String cameraCode, String productName, LocalDateTime reportTime,
                                        long total, long ok, long ng) {
        inspectionUploadRepository.syncLegacyCameraStatus(cameraCode, productName, reportTime, total, ok, ng);
    }

    private void recordException(String batchId, String requestId, Long cameraId, String cameraCode, Long productId,
                                 String productName, String sourceCode, String exceptionType, String message,
                                 String suggestion, Long factoryId, Long lineId, Long workstationId, LocalDateTime reportTime) {
        inspectionUploadRepository.recordException(batchId, requestId, cameraId, cameraCode, productId,
                productName, sourceCode, exceptionType, message, suggestion,
                factoryId, lineId, workstationId, reportTime);
    }

    private Long queryExistingBatchId(Long cameraId, String batchId) {
        return inspectionUploadRepository.queryExistingBatchId(cameraId, batchId);
    }

    private Long resolveShiftId(LocalDateTime reportTime) {
        try {
            List<Map<String, Object>> shifts = inspectionUploadRepository.listEnabledShifts();
            LocalTime t = reportTime.toLocalTime();
            for (Map<String, Object> s : shifts) {
                LocalTime start = toLocalTime(s.get("start_time"));
                LocalTime end = toLocalTime(s.get("end_time"));
                boolean cross = intVal(s.get("cross_day"), 0) == 1;
                boolean hit = cross ? (!t.isBefore(start) || t.isBefore(end)) : (!t.isBefore(start) && t.isBefore(end));
                if (hit) return longVal(s.get("shift_id"));
            }
        } catch (Exception ignored) {}
        return reportTime.getHour() >= 8 && reportTime.getHour() < 20 ? 1L : 2L;
    }

    private void fail422(InspectionBatchUploadResponse response, String type, String sourceCode, String message, String suggestion) {
        InspectionBatchUploadResponse.UploadIssue issue = new InspectionBatchUploadResponse.UploadIssue();
        issue.setIssueType(type);
        issue.setSourceCode(sourceCode);
        issue.setMessage(message);
        issue.setSuggestion(suggestion);
        response.getIssues().add(issue);
        throw new InspectionUploadException(422, message, response);
    }

    private LocalDateTime parseDateTime(String value, String field) {
        if (isBlank(value)) {
            throw new InspectionUploadException(422, field + " 不能为空", new InspectionBatchUploadResponse());
        }
        try {
            return LocalDateTime.parse(value.trim(), DT);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value.trim());
            } catch (Exception e) {
                InspectionBatchUploadResponse response = new InspectionBatchUploadResponse();
                fail422(response, "INVALID_TIME_FORMAT", null,
                        field + " 时间格式错误，应为 yyyy-MM-dd HH:mm:ss。",
                        "请按示例：2026-06-08 10:30:00 上报。");
                return null;
            }
        }
    }

    private LocalDateTime floorToBucket(LocalDateTime time, int minutes) {
        int minute = (time.getMinute() / minutes) * minutes;
        return LocalDateTime.of(LocalDate.from(time), LocalTime.of(time.getHour(), minute));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private LocalTime toLocalTime(Object value) {
        if (value instanceof LocalTime) return (LocalTime) value;
        if (value instanceof java.sql.Time) return ((java.sql.Time) value).toLocalTime();
        return LocalTime.parse(String.valueOf(value));
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return text;
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a.trim() : (!isBlank(b) ? b.trim() : "");
    }

    private long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String strOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

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
