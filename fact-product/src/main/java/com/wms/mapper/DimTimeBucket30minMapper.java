package com.wms.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.entity.DimTimeBucket30min;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.time.LocalDateTime;

public interface DimTimeBucket30minMapper extends BaseMapper<DimTimeBucket30min> {

    /**
     * 核心查询方法：根据【日期+小时+30分钟分组】查询对应的时间桶ID
     * 精准匹配DimTimeBucket30min的唯一组合条件
     */
    Integer selectBucketIdByTime(@Param("fullDate") LocalDate fullDate,
                                 @Param("hour") Byte hour,
                                 @Param("minuteGroup") String minuteGroup);

    /**
     * 兜底查询：根据时间落在【start_time-end_time】区间内，查询对应的时间桶ID
     * 双重保障，防止维度表数据匹配异常
     */
    Integer selectBucketIdByDateTime(@Param("dateTime") LocalDateTime dateTime);
}