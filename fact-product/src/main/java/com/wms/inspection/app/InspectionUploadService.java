package com.wms.inspection.app;

import com.wms.inspection.api.dto.InspectionBatchUploadRequest;
import com.wms.inspection.api.dto.InspectionBatchUploadResponse;

public interface InspectionUploadService {
    InspectionBatchUploadResponse upload(InspectionBatchUploadRequest request,
                                         String authorization,
                                         String deviceIdHeader,
                                         String batchIdHeader);
}
