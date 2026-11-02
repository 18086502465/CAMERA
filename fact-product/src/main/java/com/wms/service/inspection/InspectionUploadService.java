package com.wms.service.inspection;

import com.wms.dto.inspection.InspectionBatchUploadRequest;
import com.wms.dto.inspection.InspectionBatchUploadResponse;

public interface InspectionUploadService {
    InspectionBatchUploadResponse upload(InspectionBatchUploadRequest request,
                                         String authorization,
                                         String deviceIdHeader,
                                         String batchIdHeader);
}
