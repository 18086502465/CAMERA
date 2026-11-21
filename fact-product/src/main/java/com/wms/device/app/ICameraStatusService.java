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
     * 按主键取单台相机（不存在返回 null）。
     *
     * <p><b>用途</b>（2026-11-18 评审 M-02）：相机详情页此前只能"拉全量相机列表
     * 再本地 find 一台"（{@code getCameraList({page:1,size:999})}）——
     * 载荷浪费且随相机规模恶化。本方法提供按 id 的单查路径。
     *
     * <p>返回 {@code null} 表示不存在；<b>不返回已逻辑删除</b>的相机
     * （{@code is_active=0}），与列表口径一致。
     */
    CameraStatus getById(Long id);

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

    /**
     * 查询某相机近 N 小时的心跳历史采样（温度/状态/CPU/内存趋势）。
     *
     * <p>评审 P1-1：历史表此前无写入路径，本方法配合心跳节流采样后可用。
     * 表缺失或无数据时返回空列表（降级，不抛异常）。
     *
     * @param cameraCode 相机编号（deviceId / camera_code）
     * @param hours      回溯小时数（1~24，超出夹紧）
     * @param limit      最多返回条数（10~500，超出夹紧）
     */
    List<Map<String, Object>> getHeartbeatTrend(String cameraCode, int hours, int limit);
}
