package com.wms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.device.api.dto.CameraHeartbeatResponseDTO;
import com.wms.device.api.dto.CameraStatusHeartbeatDTO;
import com.wms.device.api.CameraStatusHeartbeatController;
import com.wms.device.app.CameraTokenVerifier;
import com.wms.device.app.ICameraStatusService;
import com.wms.device.app.SystemSettingReader;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public API contract test: POST /api/v1/camera/status/heartbeat.
 * This freezes controller HTTP contract without loading Spring context or database.
 */
@ExtendWith(MockitoExtension.class)
class CameraStatusHeartbeatControllerTest {

    private static final String URL = "/api/v1/camera/status/heartbeat";

    @Mock
    private ICameraStatusService cameraStatusService;

    /** 2026-11-12：心跳补齐了设备令牌校验（此前只判断 "Bearer " 前缀，token 从不比对） */
    @Mock
    private CameraTokenVerifier cameraTokenVerifier;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SystemSettingReader systemSettingReader;

    @InjectMocks
    private CameraStatusHeartbeatController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // DDD P4：心跳里的告警阈值读取走 SystemSettingReader 端口；null → 调用方兜底默认值
        org.mockito.Mockito.lenient().when(systemSettingReader.findSettingValue(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(null);
        // 默认令牌校验通过；各用例按需覆盖成 MISMATCH / NOT_CONFIGURED
        org.mockito.Mockito.lenient().when(cameraTokenVerifier.verify(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CameraTokenVerifier.Result.OK);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("heartbeat success returns http 200 and code 200")
    void heartbeatSuccess() throws Exception {
        CameraHeartbeatResponseDTO response = new CameraHeartbeatResponseDTO();
        response.setServerTime("2026-06-16 12:00:01");
        response.setNextInterval(5000);
        response.setConfigVersion("v1");

        when(cameraStatusService.processHeartbeat(any(CameraStatusHeartbeatDTO.class), eq("CAM-HZ-001")))
                .thenReturn(response);

        mockMvc.perform(post(URL)
                        .header("Authorization", "Bearer device-token")
                        .header("X-Device-Id", "CAM-HZ-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validHeartbeat())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.serverTime").value("2026-06-16 12:00:01"))
                .andExpect(jsonPath("$.data.nextInterval").value(5000))
                .andExpect(jsonPath("$.data.configVersion").value("v1"));
    }

    @Test
    @DisplayName("missing token returns http 200 and code 500 in current contract")
    void heartbeatMissingToken() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Device-Id", "CAM-HZ-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validHeartbeat())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        verify(cameraStatusService, never()).processHeartbeat(any(), any());
    }

    @Test
    @DisplayName("missing device id returns http 200 and code 500 in current contract")
    void heartbeatMissingDeviceId() throws Exception {
        mockMvc.perform(post(URL)
                        .header("Authorization", "Bearer device-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validHeartbeat())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        verify(cameraStatusService, never()).processHeartbeat(any(), any());
    }

    @Test
    @DisplayName("invalid status returns http 200 and code 500 in current contract")
    void heartbeatInvalidStatus() throws Exception {
        CameraStatusHeartbeatDTO request = validHeartbeat();
        request.setCameraStatus(8);

        mockMvc.perform(post(URL)
                        .header("Authorization", "Bearer device-token")
                        .header("X-Device-Id", "CAM-HZ-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        verify(cameraStatusService, never()).processHeartbeat(any(), any());
    }

    // ==================== 设备令牌校验（2026-11-12 新增） ====================

    @Test
    @DisplayName("令牌与相机不匹配时拒绝，且不进入业务处理")
    void heartbeatTokenMismatch() throws Exception {
        when(cameraTokenVerifier.verify("device-token", "CAM-HZ-001"))
                .thenReturn(CameraTokenVerifier.Result.MISMATCH);

        mockMvc.perform(post(URL)
                        .header("Authorization", "Bearer device-token")
                        .header("X-Device-Id", "CAM-HZ-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validHeartbeat())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        // 这是本次修复的核心：修复前任意串都能通过，业务照样执行
        verify(cameraStatusService, never()).processHeartbeat(any(), any());
    }

    @Test
    @DisplayName("未配置令牌 + 严格模式 → 拒绝")
    void heartbeatNotConfiguredStrict() throws Exception {
        when(cameraTokenVerifier.verify("device-token", "CAM-HZ-001"))
                .thenReturn(CameraTokenVerifier.Result.NOT_CONFIGURED);
        when(cameraTokenVerifier.rejectWhenNotConfigured()).thenReturn(true);

        mockMvc.perform(post(URL)
                        .header("Authorization", "Bearer device-token")
                        .header("X-Device-Id", "CAM-HZ-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validHeartbeat())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        verify(cameraStatusService, never()).processHeartbeat(any(), any());
    }

    @Test
    @DisplayName("未配置令牌 + 兼容模式（默认）→ 放行，避免现场未配令牌的相机停线")
    void heartbeatNotConfiguredCompat() throws Exception {
        when(cameraTokenVerifier.verify("device-token", "CAM-HZ-001"))
                .thenReturn(CameraTokenVerifier.Result.NOT_CONFIGURED);
        when(cameraTokenVerifier.rejectWhenNotConfigured()).thenReturn(false);
        when(cameraStatusService.processHeartbeat(any(CameraStatusHeartbeatDTO.class), eq("CAM-HZ-001")))
                .thenReturn(new CameraHeartbeatResponseDTO());

        mockMvc.perform(post(URL)
                        .header("Authorization", "Bearer device-token")
                        .header("X-Device-Id", "CAM-HZ-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validHeartbeat())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private CameraStatusHeartbeatDTO validHeartbeat() {
        CameraStatusHeartbeatDTO dto = new CameraStatusHeartbeatDTO();
        dto.setCameraName("HZ-Station-A-Front-Camera");
        dto.setProductName("HZ-Product-A");
        dto.setTimestamp("2026-06-16 12:00:00");
        dto.setCameraStatus(1);
        dto.setRecognizeCount(1000);
        dto.setCorrectCount(960);
        dto.setAbnormalCount(40);
        dto.setCpuUsage(45.5);
        dto.setMemoryUsage(60.0);
        dto.setTemperature(38.2);
        dto.setErrorMessage("");
        return dto;
    }
}