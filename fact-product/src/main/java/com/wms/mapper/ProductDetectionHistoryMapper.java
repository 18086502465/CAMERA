package com.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.entity.ProductDetectionHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 产品检测历史记录 Mapper（适配 product_detection_history 16列）
 */
@Mapper
public interface ProductDetectionHistoryMapper extends BaseMapper<ProductDetectionHistory> {

    /**
     * 批量插入数据
     */
    int batchInsert(@Param("list") List<ProductDetectionHistory> list);

    /**
     * 根据时间删除数据（删除 detection_time < endTime 的记录）
     */
    int deleteBeforeTime(@Param("endTime") LocalDateTime endTime);

    /**
     * 根据 detectionTime + defectCode 查询记录（幂等性：批量上报去重）
     */
    ProductDetectionHistory selectByStartTimeAndCameraName(
            @Param("startTime") LocalDateTime detectionTime,
            @Param("cameraName") String defectCode);
}
