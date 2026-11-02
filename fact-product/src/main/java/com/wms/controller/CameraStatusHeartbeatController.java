package com.wms.controller;

import com.wms.dto.CameraHeartbeatResponseDTO;
import com.wms.dto.CameraStatusHeartbeatDTO;
import com.wms.service.ICameraStatusService;
import com.wms.vo.ResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 相机实时状态心跳上报接口 Controller
 */
@RestController
@RequestMapping("/api/v1/camera/status")
@Slf4j
public class CameraStatusHeartbeatController {

    @Autowired
    private ICameraStatusService cameraStatusService;

    /**
     * 相机心跳上报接口
     * 接口路径：POST /api/v1/camera/status/heartbeat
     *
     * @param authorization Bearer token认证令牌
     * @param deviceId      相机设备编号
     * @param dto           心跳上报数据
     * @return 响应结果
     */
    @PostMapping("/heartbeat")
    public ResultVO<CameraHeartbeatResponseDTO> heartbeat(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestBody CameraStatusHeartbeatDTO dto) {

        try {
            // 1. 校验 Token（如果需要认证）
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                return ResultVO.fail("缺少认证令牌");
            }
            String token = authorization.substring(7);

            // 2. 校验设备ID
            if (deviceId == null || deviceId.trim().isEmpty()) {
                return ResultVO.fail("缺少设备ID");
            }

            // 3. 校验必填字段
            if (dto.getCameraName() == null || dto.getCameraName().trim().isEmpty()) {
                return ResultVO.fail("cameraName 不能为空");
            }

            // 4. 校验状态枚举值
            if (dto.getCameraStatus() != null) {
                if (dto.getCameraStatus() < 0 || dto.getCameraStatus() > 3) {
                    return ResultVO.fail("无效的相机状态，状态值应为 0-停止, 1-运行, 2-故障, 3-待机");
                }
            }

            // 5. 处理心跳
            CameraHeartbeatResponseDTO response = cameraStatusService.processHeartbeat(dto, deviceId);

            // 6. 返回成功响应
            return ResultVO.success(response, "状态已更新");

        } catch (IllegalArgumentException e) {
            log.warn("参数校验失败: {}", e.getMessage());
            return ResultVO.fail(e.getMessage());
        } catch (Exception e) {
            log.error("心跳处理异常", e);
            return ResultVO.fail("服务器内部错误：" + e.getMessage());
        }
    }
}
