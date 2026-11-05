package com.wms.inspection.app;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wms.inspection.api.dto.BatchUploadDTO;
import com.wms.inspection.api.dto.BatchUploadResultDTO;
import com.wms.inspection.domain.model.ProductDetectionHistory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 产品检测历史记录 Service 接口
 * 适配 product_detection_history 表（16列：单条缺陷检测记录）
 */
public interface IProductDetectionHistoryService extends IService<ProductDetectionHistory> {

    /**
     * 批量上报检测数据
     */
    BatchUploadResultDTO batchUpload(BatchUploadDTO batchUploadDTO, String deviceId, String batchId);

    /**
     * 根据工位、缺陷编码和日期范围查询检测记录
     */
    List<ProductDetectionHistory> getByWorkstationAndDefectAndDateRange(
            Long workstationId, String defectCode, LocalDate startDate, LocalDate endDate);

    /**
     * 批量保存记录
     */
    boolean batchSaveRecords(List<ProductDetectionHistory> records);

    /**
     * 清理指定时间之前的历史数据
     */
    int cleanHistoryData(LocalDateTime endTime, int batchSize);
}
