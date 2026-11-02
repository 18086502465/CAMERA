package com.wms.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 缺陷图片批量上传结果。
 */
@Data
public class DefectImageUploadResultDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Boolean success;
    private String batchNo;
    private Long batchId;
    private String cameraId;
    private String cameraName;
    private Integer imageCount = 0;
    private Integer successCount = 0;
    private Integer failCount = 0;
    private Integer defectCount = 0;
    private Integer highCount = 0;
    private Integer mediumCount = 0;
    private Integer lowCount = 0;
    private List<FileResult> results = new ArrayList<>();

    @Data
    public static class FileResult implements Serializable {
        private static final long serialVersionUID = 1L;
        private String fileName;
        private Boolean success;
        private Long imageId;
        private String imageUrl;
        private String errorCode;
        private String message;
        private String failureReason;
        private String handlingAdvice;
    }
}
