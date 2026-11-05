package com.wms.inspection.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 新版相机批量检测上报请求。
 *
 * 协议特点：
 * 1. P0 只接受 INCREMENTAL 增量计数；
 * 2. 缺陷项使用 defects[] 行式结构，不再使用固定 p0/p1 字段；
 * 3. sourceCode 由后台按“产品 + 相机 + sourceCode”映射到标准缺陷类型。
 */
@Data
public class InspectionBatchUploadRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String schemaVersion;
    private String batchId;
    private String requestId;
    private String cameraCode;
    private String productName;
    private String reportTime;
    private TimeRange timeRange;
    private String counterMode;
    private Summary summary;
    private List<DefectItem> defects = new ArrayList<>();

    @Data
    public static class TimeRange implements Serializable {
        private static final long serialVersionUID = 1L;
        private String startTime;
        private String endTime;
    }

    @Data
    public static class Summary implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long totalCount;
        private Long okCount;
        private Long ngCount;
    }

    @Data
    public static class DefectItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private String sourceCode;
        private Long count;
    }
}
