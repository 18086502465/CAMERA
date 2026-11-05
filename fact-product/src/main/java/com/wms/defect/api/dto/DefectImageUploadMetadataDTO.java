package com.wms.defect.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 缺陷图片批量上传 metadata。
 */
@Data
public class DefectImageUploadMetadataDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 相机 ID / 设备编码，协议外层字段。 */
    private String cameraId;

    /** 相机展示名称。 */
    private String cameraName;

    /** 本批次上传时间，格式：yyyy-MM-dd HH:mm:ss[.SSS]。 */
    private String uploadTime;

    /** 图片与缺陷框 metadata。 */
    private List<ImageMetadata> images;

    @Data
    public static class ImageMetadata implements Serializable {
        private static final long serialVersionUID = 1L;
        private String fileName;
        private String captureTime;
        private List<DefectMetadata> defects;
    }

    @Data
    public static class DefectMetadata implements Serializable {
        private static final long serialVersionUID = 1L;
        private String type;
        private String severity;
        private Double x;
        private Double y;
        private Double width;
        private Double height;
        private Double area;
    }
}
