package com.wms.defect.api.dto;

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
        /**
         * 是否为「设备重传同一张图」而被幂等跳过（2026-11-19 评审 N-01 修正版）。
         *
         * <p>设备端上传后未收到响应会重传同一批图 —— 此时本张图此前已入库，
         * 服务端跳过重复写入并删除刚保存的磁盘副本。
         * 该标记让设备端/运维能区分「首次上传成功」与「重传已幂等处理」。
         */
        private Boolean duplicate;
    }
}
