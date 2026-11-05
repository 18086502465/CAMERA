package com.wms.inspection.api;

import com.wms.inspection.api.dto.InspectionBatchUploadRequest;
import com.wms.inspection.api.dto.InspectionBatchUploadResponse;
import com.wms.inspection.app.InspectionUploadException;
import com.wms.inspection.app.InspectionUploadService;
import com.wms.shared.common.ResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 新版相机批量检测数据上报接口。
 *
 * POST /api/v1/camera/inspection/batch-upload
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/camera/inspection")
public class BatchUploadController {

    @Autowired
    private InspectionUploadService inspectionUploadService;

    @PostMapping("/batch-upload")
    public ResponseEntity<ResultVO<InspectionBatchUploadResponse>> batchUpload(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceIdHeader,
            @RequestHeader(value = "X-Batch-Id", required = false) String batchIdHeader,
            @RequestBody(required = false) InspectionBatchUploadRequest request) {
        try {
            InspectionBatchUploadResponse data = inspectionUploadService.upload(
                    request, authorization, deviceIdHeader, batchIdHeader);
            String message = Boolean.TRUE.equals(data.getDuplicate())
                    ? "批次已接收过，本次按幂等请求处理"
                    : "检测批次上报成功";
            return ResponseEntity.ok(ResultVO.success(data, message));
        } catch (InspectionUploadException e) {
            int status = e.getHttpStatus() <= 0 ? 422 : e.getHttpStatus();
            return ResponseEntity.status(status)
                    .body(ResultVO.fail(status, e.getMessage(), e.getResponse()));
        } catch (Exception e) {
            log.error("新版检测上报接口异常", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ResultVO.fail(500, "服务端处理异常：" + e.getMessage(), null));
        }
    }
}
