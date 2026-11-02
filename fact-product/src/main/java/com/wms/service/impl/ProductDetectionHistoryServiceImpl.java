package com.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.dto.BatchUploadDTO;
import com.wms.dto.BatchUploadResultDTO;
import com.wms.dto.BatchUploadResultDetailDTO;
import com.wms.entity.ProductDetectionHistory;
import com.wms.mapper.ProductDetectionHistoryMapper;
import com.wms.service.IProductDetectionHistoryService;
import com.wms.utils.ExcelUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 产品检测历史记录 Service 实现
 * 适配 product_detection_history 表（16列：单条缺陷检测记录）
 */
@Service
@Slf4j
public class ProductDetectionHistoryServiceImpl
        extends ServiceImpl<ProductDetectionHistoryMapper, ProductDetectionHistory>
        implements IProductDetectionHistoryService {

    @Autowired
    private ProductDetectionHistoryMapper detectionHistoryMapper;

    // ==================== 批量上报 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BatchUploadResultDTO batchUpload(BatchUploadDTO batchUploadDTO, String deviceId, String batchId) {
        BatchUploadResultDTO result = new BatchUploadResultDTO();
        String batchIdStr = (batchId != null && !batchId.trim().isEmpty())
                ? batchId
                : "BATCH" + System.currentTimeMillis();
        result.setBatchId(batchIdStr);
        result.setTotalRecords(batchUploadDTO.getDataList().size());
        result.setProcessedTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        int successCount = 0;
        int failCount = 0;
        int duplicateCount = 0;
        List<BatchUploadResultDetailDTO> details = new ArrayList<>();

        for (int i = 0; i < batchUploadDTO.getDataList().size(); i++) {
            BatchUploadDTO.DetectionDataDTO dataDTO = batchUploadDTO.getDataList().get(i);
            BatchUploadResultDetailDTO detail = new BatchUploadResultDetailDTO();
            detail.setIndex(i);
            detail.setStartTime(dataDTO.getStartTime());

            try {
                LocalDateTime startTime = ExcelUtils.parseDateTime(dataDTO.getStartTime());
                if (startTime == null) {
                    throw new IllegalArgumentException("时间格式错误，应为 yyyy-MM-dd HH:mm:ss");
                }

                ProductDetectionHistory entity = convertDTOtoEntity(dataDTO);

                ProductDetectionHistory existRecord = detectionHistoryMapper.selectByStartTimeAndCameraName(
                        entity.getDetectionTime(), entity.getDefectCode());

                if (existRecord != null) {
                    entity.setId(existRecord.getId());
                    detectionHistoryMapper.updateById(entity);
                    detail.setStatus("duplicate");
                    detail.setId(existRecord.getId());
                    detail.setMessage("记录已存在，执行更新");
                    duplicateCount++;
                } else {
                    detectionHistoryMapper.insert(entity);
                    detail.setStatus("success");
                    detail.setId(entity.getId());
                    detail.setMessage("插入成功");
                    successCount++;
                }

            } catch (Exception e) {
                log.error("处理第{}条记录失败，startTime={}", i, dataDTO.getStartTime(), e);
                detail.setStatus("fail");
                detail.setId(null);
                detail.setMessage(e.getMessage());
                failCount++;
            }
            details.add(detail);
        }

        result.setSuccessCount(successCount);
        result.setFailCount(failCount);
        result.setDuplicateCount(duplicateCount);
        result.setDetails(details);
        return result;
    }

    private ProductDetectionHistory convertDTOtoEntity(BatchUploadDTO.DetectionDataDTO dto) {
        ProductDetectionHistory entity = new ProductDetectionHistory();
        entity.setDetectionTime(ExcelUtils.parseDateTime(dto.getStartTime()));
        // NOT NULL 字段 — null-safe 默认值（兼容旧格式调用方未传参）
        entity.setLineId(dto.getLineId() != null ? dto.getLineId() : 1);
        entity.setWorkstationId(dto.getWorkstationId() != null ? dto.getWorkstationId() : 1);
        entity.setBrandId(dto.getBrandId() != null ? dto.getBrandId() : 1);
        entity.setShiftId(dto.getShiftId() != null ? dto.getShiftId() : 1);
        entity.setCaptureSpeed(dto.getCaptureSpeed() != null ? dto.getCaptureSpeed() : "UNKNOWN");
        entity.setResult(dto.getResult() != null ? dto.getResult() : "NG");
        entity.setDefectCode(dto.getCameraName());
        entity.setDefectArea(dto.getDefectArea() != null ? dto.getDefectArea() : "");
        entity.setDefectType(dto.getDefectType() != null ? dto.getDefectType() : "");
        entity.setDefectSize(dto.getDefectSize() != null ? dto.getDefectSize() : "");
        entity.setImagePath(dto.getImagePath() != null ? dto.getImagePath() : "");
        entity.setConfidence(dto.getConfidence() != null
                ? new BigDecimal(String.valueOf(dto.getConfidence()))
                : BigDecimal.ZERO);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        return entity;
    }

    // ==================== 按条件查询 ====================

    @Override
    public List<ProductDetectionHistory> getByWorkstationAndDefectAndDateRange(
            Long workstationId, String defectCode, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<ProductDetectionHistory> wrapper = new LambdaQueryWrapper<>();
        if (workstationId != null) {
            wrapper.eq(ProductDetectionHistory::getWorkstationId, workstationId);
        }
        if (defectCode != null && !defectCode.trim().isEmpty()) {
            wrapper.eq(ProductDetectionHistory::getDefectCode, defectCode);
        }
        if (startDate != null) {
            wrapper.ge(ProductDetectionHistory::getDetectionTime, startDate.atStartOfDay());
        }
        if (endDate != null) {
            wrapper.le(ProductDetectionHistory::getDetectionTime, endDate.plusDays(1).atStartOfDay());
        }
        wrapper.orderByAsc(ProductDetectionHistory::getDetectionTime);
        return list(wrapper);
    }

    // ==================== 批量保存 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean batchSaveRecords(List<ProductDetectionHistory> records) {
        if (records.isEmpty()) {
            return true;
        }
        int batchSize = 500;
        int totalSize = records.size();
        for (int i = 0; i < totalSize; i += batchSize) {
            int end = Math.min(i + batchSize, totalSize);
            List<ProductDetectionHistory> batchList = records.subList(i, end);
            int insertCount = detectionHistoryMapper.batchInsert(batchList);
            if (insertCount != batchList.size()) {
                log.error("批量插入失败，期望插入{}条，实际插入{}条", batchList.size(), insertCount);
                throw new RuntimeException("批量插入数据失败");
            }
        }
        return true;
    }

    // ==================== 数据清理 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cleanHistoryData(LocalDateTime endTime, int batchSize) {
        if (endTime == null || batchSize <= 0) {
            log.warn("清理历史数据参数无效，结束时间：{}，批次大小：{}", endTime, batchSize);
            return 0;
        }
        int totalDeleteCount = 0;
        int deleteCount;
        do {
            deleteCount = detectionHistoryMapper.deleteBeforeTime(endTime);
            totalDeleteCount += deleteCount;
            log.info("清理历史数据，本次删除{}条，累计删除{}条", deleteCount, totalDeleteCount);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (deleteCount >= batchSize);
        return totalDeleteCount;
    }
}
