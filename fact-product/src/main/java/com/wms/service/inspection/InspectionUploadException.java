package com.wms.service.inspection;

import com.wms.dto.inspection.InspectionBatchUploadResponse;

/**
 * 设备检测上报业务校验异常。
 */
public class InspectionUploadException extends RuntimeException {
    private final int httpStatus;
    private final InspectionBatchUploadResponse response;

    public InspectionUploadException(int httpStatus, String message, InspectionBatchUploadResponse response) {
        super(message);
        this.httpStatus = httpStatus;
        this.response = response;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public InspectionBatchUploadResponse getResponse() {
        return response;
    }
}
