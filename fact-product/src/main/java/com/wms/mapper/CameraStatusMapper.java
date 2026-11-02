package com.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.entity.CameraStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 相机状态 Mapper
 */
@Mapper
public interface CameraStatusMapper extends BaseMapper<CameraStatus> {

    /** 按状态统计数量 */
    @Select("SELECT status, COUNT(*) AS count FROM camera_status GROUP BY status")
    List<Map<String, Object>> countByStatus();

    /** 各相机识别统计（Top 10） */
    @Select("SELECT camera_name, cpu_usage, memory_usage FROM camera_status ORDER BY cpu_usage DESC LIMIT 10")
    List<Map<String, Object>> getRecognizeStats();
}
