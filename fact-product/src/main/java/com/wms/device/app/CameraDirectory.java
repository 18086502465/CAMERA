package com.wms.device.app;

/**
 * 相机目录端口（DDD P4：消费方视角的跨域接口）。
 *
 * <p>analytics 域（看板）需要对相机档案做唯一性校验与依赖计数，
 * 但按依赖铁律不允许直接注入 device 域的 Repository —— 通过本接口访问。
 * device 域提供实现（{@link com.wms.device.app.CameraDirectoryAdapter}）。
 *
 * <p>方法集合 = 消费方（CameraDashboardController）实际使用的最小集。
 */
public interface CameraDirectory {

    /** 相机编号在 dim_camera 中已存在（新增时唯一性校验） */
    int countDimCameraByCode(String cameraCode);

    /** 相机编号已被其它相机占用（编辑时唯一性校验，排除自身） */
    int countDimCameraByCodeExcept(String cameraCode, Long cameraId);

    /** 该相机的缺陷图片文件数（依赖计数） */
    int countDefectImageFiles(String cameraCode, String cameraName);

    /** 该相机名在兼容表（camera_images）中的图片数（依赖计数） */
    int countCompatImages(String cameraName);

    /** 该相机的缺陷框数（依赖计数） */
    int countDefectImageBoxes(String cameraCode, String cameraName);

    /** 该相机在缺陷检测配置中的映射数（依赖计数） */
    int countDefectMappings(Long cameraId);

    /** 该相机的上报令牌数（依赖计数） */
    int countCameraTokens(Long cameraId);

    /** 该相机的心跳记录数（依赖计数） */
    int countHeartbeats(String cameraCode);

    // ---- 以下为相机删除/保存的级联操作（CameraDashboardController 实际使用）----

    /** 状态快照逻辑删除 */
    int softDeleteCameraStatus(Long id);

    /** 相机档案逻辑删除 */
    int softDeleteDimCamera(Long cameraId);

    /** 删除该相机的缺陷检测配置映射，返回行数 */
    int deleteDefectMappings(Long cameraId);

    /** 删除该相机的上报令牌，返回行数 */
    int deleteCameraTokens(Long cameraId);

    /** 新增/更新相机档案（upsert），返回影响行数 */
    int upsertDimCamera(Long cameraId, Integer factoryId, Integer lineId, Integer workstationId,
                        String cameraCode, String cameraName);

    /** 相机换绑后联动缺陷映射位置，返回行数 */
    int syncMappingLocation(Integer factoryId, Integer lineId, Integer workstationId,
                            String cameraCode, Long cameraId);
}
