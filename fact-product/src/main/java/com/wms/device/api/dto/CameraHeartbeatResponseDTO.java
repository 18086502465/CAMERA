package com.wms.device.api.dto;

import lombok.Data;
import java.io.Serializable;

/**
 * 相机心跳响应 DTO
 */
@Data
public class CameraHeartbeatResponseDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 服务器时间
     */
    private String serverTime;

    /**
     * 下次上报间隔（毫秒）
     */
    private Integer nextInterval;

    /**
     * 配置版本号
     */
    private String configVersion;
}
