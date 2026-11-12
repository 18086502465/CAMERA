package com.wms.device.api;

import com.wms.device.api.dto.CameraHeartbeatResponseDTO;
import com.wms.device.api.dto.CameraStatusHeartbeatDTO;
import com.wms.device.app.CameraTokenVerifier;
import com.wms.device.app.ICameraStatusService;
import com.wms.shared.common.ResultVO;
import com.wms.shared.util.LogThrottle;
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

    @Autowired
    private CameraTokenVerifier cameraTokenVerifier;

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
            // 1. 校验认证令牌
            // ★ 2026-11-12 修复：原实现只判断 Authorization 是否以 "Bearer " 开头，
            //   substring(7) 取出的 token 从未与 cfg_camera_token 比对（变量取了就丢弃）
            //   → 本端点是 permitAll，等于匿名可写：任何人可伪造任意相机心跳、
            //   篡改累计检测数、触发告警。
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                return ResultVO.fail("缺少认证令牌");
            }
            String token = authorization.substring(7);

            // 2. 校验设备ID —— 它同时是相机身份（camera_code），令牌校验需要它
            if (deviceId == null || deviceId.trim().isEmpty()) {
                return ResultVO.fail("缺少设备ID");
            }

            // 3. 校验令牌与相机的绑定关系
            CameraTokenVerifier.Result tokenResult = cameraTokenVerifier.verify(token, deviceId.trim());
            if (tokenResult == CameraTokenVerifier.Result.MISMATCH) {
                log.warn("设备令牌无效: deviceId={}", deviceId);
                return ResultVO.fail("设备令牌无效或已过期");
            }
            if (tokenResult == CameraTokenVerifier.Result.NOT_CONFIGURED) {
                if (cameraTokenVerifier.rejectWhenNotConfigured()) {
                    log.warn("相机未配置设备令牌，严格模式拒绝: deviceId={}", deviceId);
                    return ResultVO.fail("该相机未配置设备令牌");
                }
                // 心跳 5 秒/台，这条告警必须节流：实测 16 台相机一分钟 34 条、
                // 占该时段日志 45%，不节流会把真实告警埋掉。
                if (LogThrottle.allow("token-not-configured:" + deviceId, 10 * 60 * 1000L)) {
                    log.warn("相机未配置设备令牌，心跳按兼容模式放行: deviceId={} —— "
                            + "配置后建议开启 camera.token.strict（本告警每相机 10 分钟最多一条）", deviceId);
                }
            }

            // 4. 校验必填字段
            if (dto.getCameraName() == null || dto.getCameraName().trim().isEmpty()) {
                return ResultVO.fail("cameraName 不能为空");
            }

            // 5. 校验状态枚举值
            if (dto.getCameraStatus() != null) {
                if (dto.getCameraStatus() < 0 || dto.getCameraStatus() > 3) {
                    return ResultVO.fail("无效的相机状态，状态值应为 0-停止, 1-运行, 2-故障, 3-待机");
                }
            }

            // 6. 处理心跳
            CameraHeartbeatResponseDTO response = cameraStatusService.processHeartbeat(dto, deviceId);

            // 7. 返回成功响应
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
