package com.wms.inspection.api.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * 批量上报响应体 DTO
 */
@Data
public class BatchUploadResultDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 批次号
     */
    private String batchId;

    /**
     * 总记录数
     */
    private Integer totalRecords;

    /**
     * 成功条数
     */
    private Integer successCount;

    /**
     * 失败条数
     */
    private Integer failCount;

    /**
     * 重复条数（幂等性控制）
     */
    private Integer duplicateCount;

    /**
     * 处理完成时间
     */
    private String processedTime;

    /**
     * 处理详情
     */
    private List<BatchUploadResultDetailDTO> details;
}
