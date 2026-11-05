package com.wms.inspection.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 新版相机批量检测上报响应。
 */
@Data
public class InspectionBatchUploadResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private Boolean accepted = false;
    private Boolean duplicate = false;
    private String batchId;
    private Long inspectionBatchId;
    private Long cameraId;
    private String cameraCode;
    private Long productId;
    private String productName;
    private Long totalCount = 0L;
    private Long okCount = 0L;
    private Long ngCount = 0L;
    private Long mappedDefectCount = 0L;
    private Long unmappedDefectCount = 0L;
    private String timeBucket30m;
    private List<MappedDefect> mappedDefects = new ArrayList<>();
    private List<UploadIssue> issues = new ArrayList<>();

    @Data
    public static class MappedDefect implements Serializable {
        private static final long serialVersionUID = 1L;
        private String sourceCode;
        private Long mappingId;
        private Long defectTypeId;
        private String defectCode;
        private String defectName;
        private String severity;
        private Long count;
    }

    @Data
    public static class UploadIssue implements Serializable {
        private static final long serialVersionUID = 1L;
        private String sourceCode;
        private String issueType;
        private String message;
        private String suggestion;
    }
}
