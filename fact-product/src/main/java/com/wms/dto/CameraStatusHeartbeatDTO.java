package com.wms.dto;

import lombok.Data;
import java.io.Serializable;

/**
 * 相机实时状态心跳上报请求 DTO
 */
@Data
public class CameraStatusHeartbeatDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 相机名称
     */
    private String cameraName;

    /**
     * 当前生产产品
     */
    private String productName;

    /**
     * 上报时间 yyyy-MM-dd HH:mm:ss
     */
    private String timestamp;

    /**
     * 相机运行状态：0-停止, 1-运行, 2-故障, 3-待机
     */
    private Integer cameraStatus;

    /**
     * 识别总数（累计）
     */
    private Integer recognizeCount;

    /**
     * 识别正确数（累计）
     */
    private Integer correctCount;

    /**
     * 识别异常数（累计）
     */
    private Integer abnormalCount;

    /**
     * CPU使用率 (0-100)
     */
    private Double cpuUsage;

    /**
     * 内存使用率 (0-100)
     */
    private Double memoryUsage;

    /**
     * 设备温度 (摄氏度)
     */
    private Double temperature;

    /**
     * 故障错误信息（状态为2时填写）
     */
    private String errorMessage;

    /**
     * 重置标志（可选）
     * 若相机端重置了计数器，建议在上报中携带 resetFlag: true
     */
    private Boolean resetFlag;
}
