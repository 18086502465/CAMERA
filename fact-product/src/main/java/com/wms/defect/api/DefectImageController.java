package com.wms.defect.api;

import com.wms.defect.api.dto.DefectImageUploadResultDTO;
import com.wms.defect.app.IDefectImageService;
import com.wms.shared.common.ResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 缺陷图片批量上传与查询接口。
 */
@RestController
@RequestMapping("/api/v1/defect-images")
@Slf4j
public class DefectImageController {

    @Autowired
    private IDefectImageService defectImageService;

    /** 归档任务（手动触发放这里，便于运维与验证；日常由 @Scheduled 驱动） */
    @Autowired
    private com.wms.defect.infra.task.DefectImageArchiveTask defectImageArchiveTask;

    @Value("${defect-image.max-batch-images:100}")
    private int maxBatchImages;

    /**
     * POST /api/v1/defect-images/upload
     * form-data: files=file[]，metadata=JSON 字符串。
     */
    @PostMapping("/upload")
    public ResponseEntity<ResultVO<DefectImageUploadResultDTO>> upload(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam(value = "file", required = false) MultipartFile[] file,
            @RequestParam(value = "metadata", required = false) String metadata) {
        try {
            MultipartFile[] actualFiles = mergeFiles(files, file);
            if (actualFiles.length > maxBatchImages) {
                throw new IllegalArgumentException("单批次最多支持 " + maxBatchImages + " 张图片，当前上传 " + actualFiles.length + " 张；请拆分为多个批次后重试");
            }
            DefectImageUploadResultDTO result = defectImageService.upload(actualFiles, metadata, authorization, deviceId);
            if (Boolean.TRUE.equals(result.getSuccess())) {
                return ResponseEntity.ok(ResultVO.success(result, "缺陷图片上传成功"));
            }
            return ResponseEntity.ok(ResultVO.success(result, "缺陷图片部分上传失败"));
        } catch (IllegalArgumentException e) {
            log.warn("缺陷图片上传参数错误: {}", e.getMessage());
            DefectImageUploadResultDTO failureResult = buildValidationFailureResult(e.getMessage(), deviceId);
            // QUAL-02（v3 H-5）：HTTP 422 与 body.code 早就对齐了，缺的是**机器可读的错误标识** ——
            // 监控只能靠 message 文本分类。这里补 errorCode（如 UPLOAD_BATCH_SIZE_EXCEEDED）。
            // ⚠ 刻意不改 code 数值（仍为 422/500）：现场设备固件可能按 body.code 判定重试策略，
            //   而 errorCode 是新增字段，老固件忽略即可。
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(ResultVO.<DefectImageUploadResultDTO>fail(422, e.getMessage(), failureResult)
                            .withErrorCode(mapValidationErrorCode(e.getMessage())));
        } catch (Exception e) {
            log.error("缺陷图片上传异常", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ResultVO.<DefectImageUploadResultDTO>fail(500, "缺陷图片上传失败：" + e.getMessage(), null)
                            .withErrorCode("UPLOAD_INTERNAL_ERROR"));
        }
    }

    /**
     * 参数校验错误 → 错误码族（QUAL-02）。
     *
     * <p>与 {@link #classifyValidationErrorCode} 的分类口径一致，但返回的是
     * {@link com.wms.shared.common.ApiErrorCode} 里的族编码（422xx / 401xx），
     * 便于网关与监控聚合。
     */
    private String mapValidationErrorCode(String message) {
        String code = classifyValidationErrorCode(message);
        if ("BATCH_SIZE_EXCEEDED".equals(code)) return "UPLOAD_BATCH_SIZE_EXCEEDED";
        if ("FILE_NAME_MISMATCH".equals(code)) return "UPLOAD_FILE_NAME_MISMATCH";
        if ("METADATA_INVALID".equals(code)) return "UPLOAD_METADATA_INVALID";
        if ("AUTH_OR_DEVICE_INVALID".equals(code)) return "AUTH_DEVICE_UNAUTHORIZED";
        return "PARAM_INVALID";
    }

    private DefectImageUploadResultDTO buildValidationFailureResult(String message, String deviceId) {
        DefectImageUploadResultDTO result = new DefectImageUploadResultDTO();
        result.setSuccess(false);
        result.setCameraId(deviceId);
        result.setCameraName(deviceId);
        result.setImageCount(0);
        result.setSuccessCount(0);
        result.setFailCount(1);

        DefectImageUploadResultDTO.FileResult fileResult = new DefectImageUploadResultDTO.FileResult();
        fileResult.setFileName(extractProblemFileName(message));
        fileResult.setSuccess(false);
        fileResult.setErrorCode(classifyValidationErrorCode(message));
        fileResult.setMessage(message);
        fileResult.setFailureReason(validationFailureReason(message));
        fileResult.setHandlingAdvice(validationHandlingAdvice(message));
        result.getResults().add(fileResult);
        return result;
    }

    private String extractProblemFileName(String message) {
        if (message == null) return "metadata/files";
        String[] markers = new String[]{"缺少 metadata 文件:", "未在 metadata 声明的上传文件:", "重复文件名:"};
        for (String marker : markers) {
            int idx = message.indexOf(marker);
            if (idx >= 0) {
                String tail = message.substring(idx + marker.length()).trim();
                int sep = tail.indexOf('；');
                return sep >= 0 ? tail.substring(0, sep).trim() : tail;
            }
        }
        return "metadata/files";
    }

    private String classifyValidationErrorCode(String message) {
        String msg = message == null ? "" : message;
        if (msg.contains("单批次最多支持")) return "BATCH_SIZE_EXCEEDED";
        if (msg.contains("fileName") || msg.contains("一一对应") || msg.contains("匹配") || msg.contains("重复文件名")) return "FILE_NAME_MISMATCH";
        if (msg.contains("metadata")) return "METADATA_INVALID";
        if (msg.contains("认证") || msg.contains("设备ID")) return "AUTH_OR_DEVICE_INVALID";
        return "REQUEST_INVALID";
    }

    private String validationFailureReason(String message) {
        String code = classifyValidationErrorCode(message);
        if ("BATCH_SIZE_EXCEEDED".equals(code)) return "单批次图片数量超过服务端限制";
        if ("FILE_NAME_MISMATCH".equals(code)) return "metadata 文件名与实际上传文件不一致";
        if ("METADATA_INVALID".equals(code)) return "metadata JSON 或必填字段不符合协议";
        if ("AUTH_OR_DEVICE_INVALID".equals(code)) return "认证 Header 或设备编号缺失/无效";
        return "上传请求参数校验失败";
    }

    private String validationHandlingAdvice(String message) {
        String code = classifyValidationErrorCode(message);
        if ("BATCH_SIZE_EXCEEDED".equals(code)) return "请将图片拆分为多个批次上传，默认每批最多 100 张。";
        if ("FILE_NAME_MISMATCH".equals(code)) return "请确认 multipart 字段 files/file 中的原始文件名，与 metadata.images[].fileName 完全一致，包含扩展名，且无重复。";
        if ("METADATA_INVALID".equals(code)) return "请按接口协议重新生成 metadata，确认 cameraId、uploadTime、images[].fileName 等字段正确。";
        if ("AUTH_OR_DEVICE_INVALID".equals(code)) return "请在 Header 中传 Authorization: Bearer <token> 和 X-Device-Id。";
        return "请检查请求格式、必填字段、文件名映射、图片数量和上传目录权限后重试。";
    }

    private MultipartFile[] mergeFiles(MultipartFile[] files, MultipartFile[] file) {
        List<MultipartFile> merged = new ArrayList<>();
        if (files != null) {
            for (MultipartFile f : files) {
                if (f != null) merged.add(f);
            }
        }
        if (file != null) {
            for (MultipartFile f : file) {
                if (f != null) merged.add(f);
            }
        }
        return merged.toArray(new MultipartFile[0]);
    }

    /**
     * GET /api/v1/defect-images/recent
     * 查询最近缺陷图片，用于图像记录和相机状态页复用。
     */
    @GetMapping("/recent")
    public ResultVO<List<Map<String, Object>>> recent(
            @RequestParam(required = false, defaultValue = "1000") Integer limit,
            @RequestParam(required = false) String cameraId,
            @RequestParam(required = false) String cameraName,
            @RequestParam(required = false) Integer factoryId,
            @RequestParam(required = false) Integer lineId,
            @RequestParam(required = false) Integer stationId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String defectType,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        return ResultVO.success(defectImageService.listRecent(limit, cameraId, cameraName, factoryId, lineId, stationId,
                keyword, severity, defectType, startTime, endTime));
    }

    /**
     * GET /api/v1/defect-images/camera-summaries
     * 按相机聚合图片数量和最近图片，用于图像记录页一行一个相机对象展示。
     */
    @GetMapping("/camera-summaries")
    public ResultVO<List<Map<String, Object>>> cameraSummaries(
            @RequestParam(required = false, defaultValue = "5") Integer latestLimit,
            @RequestParam(required = false) String cameraId,
            @RequestParam(required = false) String cameraName,
            @RequestParam(required = false) Integer factoryId,
            @RequestParam(required = false) Integer lineId,
            @RequestParam(required = false) Integer stationId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String defectType,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        return ResultVO.success(defectImageService.listCameraSummaries(latestLimit, cameraId, cameraName, factoryId, lineId, stationId,
                keyword, severity, defectType, startTime, endTime));
    }

    /**
     * GET /api/v1/defect-images/history
     * 查询单个相机的图片历史分页，避免前端只加载全局最近 N 条导致数量不一致。
     */
    @GetMapping("/history")
    public ResultVO<Map<String, Object>> history(
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size,
            @RequestParam(required = false) String cameraId,
            @RequestParam(required = false) String cameraName,
            @RequestParam(required = false) Integer factoryId,
            @RequestParam(required = false) Integer lineId,
            @RequestParam(required = false) Integer stationId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String defectType,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        return ResultVO.success(defectImageService.listHistoryPage(page, size, cameraId, cameraName, factoryId, lineId, stationId,
                keyword, severity, defectType, startTime, endTime));
    }

    /**
     * POST /api/v1/defect-images/archive/run
     * 手动触发一轮图片月度归档（运维/验证用；日常由 DefectImageArchiveTask 每日 02:30 执行）。
     * <p>把超过 hot-days（默认 31 天）的图片与缺陷框按月搬入 hist_YYYYMM 月表，
     * 使主表恒定轻量。返回归档行数、超期删除的月表等统计。</p>
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/archive/run")
    public ResultVO<Map<String, Object>> runArchive() {
        return ResultVO.success(defectImageArchiveTask.runOnce());
    }

    /**
     * GET /api/v1/defect-images/file/**
     * 读取本地缺陷图片文件。
     */
    @GetMapping("/file/**")
    public ResponseEntity<Resource> file(HttpServletRequest request) {
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String prefix = "/api/v1/defect-images/file/";
        String relativePath = path != null && path.startsWith(prefix) ? path.substring(prefix.length()) : "";
        return defectImageService.loadImage(relativePath, request);
    }
}
