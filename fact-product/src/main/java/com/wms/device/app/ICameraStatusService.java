package com.wms.device.app;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wms.device.api.dto.CameraHeartbeatResponseDTO;
import com.wms.device.api.dto.CameraStatusHeartbeatDTO;
import com.wms.device.domain.model.CameraStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 相机状态服务接口
 */
public interface ICameraStatusService {

    /**
     * 处理相机心跳上报
     */
    CameraHeartbeatResponseDTO processHeartbeat(CameraStatusHeartbeatDTO dto, String deviceId);

    /**
     * 查询所有相机状态
     */
    List<CameraStatus> listAll();

    /**
     * 分页查询（支持关键词搜索、状态筛选、cameraCode 集合过滤）
     */
    IPage<CameraStatus> pageQuery(Integer pageNum, Integer pageSize, String keyword, Integer status, Set<Integer> workstationIds);

    /**
     * 获取状态统计数据
     */
    Map<String, Object> getStats();

    /**
     * 获取识别统计数据
     */
    List<Map<String, Object>> getRecognizeStats();
}
