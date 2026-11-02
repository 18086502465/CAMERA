package com.wms.dto;

import lombok.Data;
import java.io.Serializable;

/**
 * 批量上报处理详情 DTO
 */
@Data
public class BatchUploadResultDetailDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 记录在数组中的索引
     */
    private Integer index;

    /**
     * 开始时间（用于标识记录）
     */
    private String startTime;

    /**
     * 处理状态：success / fail / duplicate
     */
    private String status;

    /**
     * 数据库记录ID（成功时返回）
     */
    private Long id;

    /**
     * 处理消息
     */
    private String message;
}
