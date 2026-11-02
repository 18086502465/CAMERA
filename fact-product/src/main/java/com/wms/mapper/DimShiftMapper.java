package com.wms.mapper;

import com.wms.entity.DimShift;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalTime;

/**
 * <p>
 * 班次维度表 Mapper 接口
 * </p>
 *
 * @author 45344
 * @since 2026-01-15
 */
@Mapper
public interface DimShiftMapper extends BaseMapper<DimShift> {

    /**
     * 核心查询：根据【具体时间点】匹配对应的班次ID
     * 匹配规则：传入的时间 在 班次的start_time ~ end_time 区间内
     * 完美适配 DimShift 表的业务逻辑
     */
    Integer selectShiftIdByTime(@Param("currentTime") LocalTime currentTime);

}
