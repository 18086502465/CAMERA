package com.wms.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * 批量上报请求体 DTO
 * 适配 product_detection_history 表（16列：单条缺陷检测记录）
 */
@Data
public class BatchUploadDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 相机设备编号 */
    private String deviceId;

    /** 批次上报时间 */
    private String batchTime;

    /** 本批次记录数量 */
    private Integer recordCount;

    /** 检测数据数组 */
    private List<DetectionDataDTO> dataList;

    /**
     * 内部类：单条检测数据 — 匹配 product_detection_history 表字段
     */
    @Data
    public static class DetectionDataDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 检测时间 yyyy-MM-dd HH:mm:ss（映射 detection_time） */
        private String startTime;

        /**
         * 结束时间 yyyy-MM-dd HH:mm:ss。
         *
         * 当前正式业务已确认后续会重构为新版累计/增量计数协议；这里先保留
         * 测试用例和历史调用方已使用的字段，避免 testCompile 阶段失败。
         */
        private String endTime;

        /** 当前生产产品名称，后续通过品牌表映射 brandId */
        private String productName;

        /** 产线ID */
        private Integer lineId;

        /** 工位ID */
        private Integer workstationId;

        /** 产品品牌ID */
        private Integer brandId;

        /** 班次ID（1=白班, 2=夜班） */
        private Integer shiftId;

        /** 拍摄速度 HIGH/LOW（映射 capture_speed） */
        private String captureSpeed;

        /** 检测结果 OK/NG（映射 result） */
        private String result;

        /** 缺陷编码（映射 defect_code，兼容旧 cameraName） */
        private String cameraName;

        /** 累计识别总数 */
        private Integer totalCount;

        /** 累计 OK 数 */
        private Integer okCount;

        /** 累计 NG 数 */
        private Integer ngCount;

        /** 本批次增量总数 */
        private Integer incrementalTotal;

        /** 本批次增量 OK 数 */
        private Integer incrementalOk;

        /** 本批次增量 NG 数 */
        private Integer incrementalNg;

        /** 缺陷区域 */
        private String defectArea;

        /** 缺陷类型 */
        private String defectType;

        /** 缺陷尺寸 */
        private String defectSize;

        /** 图片路径 */
        private String imagePath;

        /** 置信度 */
        private Double confidence;

        /** 记录类型：1=正常检测记录，2=异常记录 */
        private Integer recordType;

        /** 异常类型 */
        private Integer exceptionType;

        /** 异常项说明 */
        private String exceptionItems;

        /** 胶水/材料接头缺陷计数 */
        private Integer materialJoint;

        private Integer innerGlue13;
        private Integer innerGlue3Plus;
        private Integer frontWaistGlue13;
        private Integer frontWaistGlue3Plus;
        private Integer backWaistGlue13;
        private Integer backWaistGlue3Plus;
        private Integer innerOuterGlue13;
        private Integer innerOuterGlue3Plus;

        private Integer innerDefect1;
        private Integer innerDefect13;
        private Integer innerDefect3Plus;
        private Integer frontWaistDefect1;
        private Integer frontWaistDefect13;
        private Integer frontWaistDefect3Plus;
        private Integer backWaistDefect1;
        private Integer backWaistDefect13;
        private Integer backWaistDefect3Plus;
        private Integer innerOuterDefect1;
        private Integer innerOuterDefect13;
        private Integer innerOuterDefect3Plus;
    }
}
