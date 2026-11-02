package com.wms.controller;

import com.wms.entity.ProductDetectionHistory;
import com.wms.service.IProductDetectionHistoryService;
import com.wms.vo.ResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 产品检测历史记录 Controller
 * 适配 product_detection_history 表（单条缺陷检测记录）
 */
@RestController
@RequestMapping("/api/detection-history")
@Slf4j
public class ProductDetectionHistoryController {

    @Autowired
    private IProductDetectionHistoryService detectionHistoryService;

    /**
     * 根据条件查询检测记录（按 detectionTime 过滤）
     */
    @GetMapping("/query")
    public ResultVO<List<ProductDetectionHistory>> queryDetectionRecords(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @RequestParam(required = false) String defectCode,
            @RequestParam(required = false) Integer lineId,
            @RequestParam(required = false) Integer workstationId) {

        try {
            List<ProductDetectionHistory> records = detectionHistoryService.list(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProductDetectionHistory>()
                            .ge(startTime != null, ProductDetectionHistory::getDetectionTime, startTime)
                            .le(endTime != null, ProductDetectionHistory::getDetectionTime, endTime)
                            .eq(defectCode != null && !defectCode.trim().isEmpty(),
                                    ProductDetectionHistory::getDefectCode, defectCode)
                            .eq(lineId != null, ProductDetectionHistory::getLineId, lineId)
                            .eq(workstationId != null, ProductDetectionHistory::getWorkstationId, workstationId)
                            .orderByDesc(ProductDetectionHistory::getDetectionTime)
            );
            return ResultVO.success(records, String.format("查询成功，共%d条记录", records.size()));
        } catch (Exception e) {
            log.error("查询检测记录异常", e);
            return ResultVO.fail("查询失败：" + e.getMessage());
        }
    }

    /**
     * 清理历史数据
     */
    @DeleteMapping("/clean-history")
    public ResultVO<Integer> cleanHistoryData(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @RequestParam(defaultValue = "1000") int batchSize) {

        try {
            if (endTime == null) {
                return ResultVO.fail("结束时间不能为空");
            }
            LocalDateTime minKeepTime = LocalDateTime.now().minusMonths(3);
            if (endTime.isAfter(minKeepTime)) {
                return ResultVO.fail(String.format("为防止数据丢失，只能清理%s之前的数据", minKeepTime));
            }
            int deleteCount = detectionHistoryService.cleanHistoryData(endTime, batchSize);
            return ResultVO.success(deleteCount, String.format("历史数据清理成功，共删除%d条记录", deleteCount));
        } catch (Exception e) {
            log.error("清理历史数据异常", e);
            return ResultVO.fail("清理失败：" + e.getMessage());
        }
    }
}
