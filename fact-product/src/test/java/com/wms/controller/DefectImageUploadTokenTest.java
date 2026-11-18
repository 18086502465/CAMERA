package com.wms.controller;

import com.wms.defect.api.DefectImageController;
import com.wms.defect.app.IDefectImageService;
import com.wms.device.app.CameraTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 缺陷图片上传端点的设备令牌校验契约测试（2026-11-18 评审 M-03）。
 *
 * <p>背景：本端点此前<b>只校验 Authorization 以 "Bearer " 开头，从不比对令牌</b> ——
 * 而心跳与批量上报两个端点都比对了。任何非空 Bearer 串 + 合法 X-Device-Id
 * 即可写图片与缺陷框 → 污染统计与良率口径（现场 2.4 万张图全走这条通道）。
 *
 * <p>本测试冻结：**上传端点与心跳同一口径**。
 */
@ExtendWith(MockitoExtension.class)
class DefectImageUploadTokenTest {

    private static final String URL = "/api/v1/defect-images/upload";

    @Mock
    private IDefectImageService defectImageService;
    @Mock
    private CameraTokenVerifier cameraTokenVerifier;

    @InjectMocks
    private DefectImageController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "maxBatchImages", 100);
        lenient().when(cameraTokenVerifier.rejectWhenNotConfigured()).thenReturn(false);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("files", "a.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1, 2, 3});
    }

    /** metadata 是 **Text 型表单字段**（不是文件）—— Controller 的参数类型是 String */
    private static final String METADATA_JSON =
            "{\"cameraId\":\"DA4733319\",\"images\":[{\"fileName\":\"a.jpg\"}]}";

    private MockHttpServletRequestBuilder req() {
        // ★ 必须用带文件参数的重载 multipart(URL, file...) ——
        //   单参 multipart(URL) 返回的是 MockHttpServletRequestBuilder，
        //   无法 chain .file()/.param() 成 multipart 请求。
        return multipart(URL, file()).param("metadata", METADATA_JSON);
    }

    @Test
    @DisplayName("令牌与相机不匹配 → 401 DEVICE_TOKEN_INVALID（设备端应停止重试）")
    void mismatchedTokenRejected() throws Exception {
        when_verify(CameraTokenVerifier.Result.MISMATCH);

        mockMvc.perform(req()
                        .header("Authorization", "Bearer wrong-token")
                        .header("X-Device-Id", "DA4733319"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.errorCode").value("DEVICE_TOKEN_INVALID"));

        // ★ 关键断言：令牌不对时**绝不能**进入业务落库
        verify(defectImageService, never()).upload(any(), any(), any(), any());
    }

    @Test
    @DisplayName("缺 Authorization 或 X-Device-Id → 422 AUTH_DEVICE_UNAUTHORIZED")
    void missingAuthOrDeviceIdRejected() throws Exception {
        mockMvc.perform(req()
                        .header("X-Device-Id", "DA4733319"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("AUTH_DEVICE_UNAUTHORIZED"));

        mockMvc.perform(req()
                        .header("Authorization", "Bearer some-token"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("AUTH_DEVICE_UNAUTHORIZED"));

        verify(defectImageService, never()).upload(any(), any(), any(), any());
    }

    @Test
    @DisplayName("相机未配置令牌 + 严格模式 → 401 DEVICE_TOKEN_NOT_CONFIGURED")
    void strictModeRejectsUnconfiguredCamera() throws Exception {
        when_verify(CameraTokenVerifier.Result.NOT_CONFIGURED);
        lenient().when(cameraTokenVerifier.rejectWhenNotConfigured()).thenReturn(true);

        mockMvc.perform(req()
                        .header("Authorization", "Bearer any")
                        .header("X-Device-Id", "DA4733319"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DEVICE_TOKEN_NOT_CONFIGURED"));

        verify(defectImageService, never()).upload(any(), any(), any(), any());
    }

    @Test
    @DisplayName("相机未配置令牌 + 非严格模式 → 放行（现场现状：令牌表为空 + strict=false）")
    void nonStrictModeAllowsUnconfiguredCamera() throws Exception {
        when_verify(CameraTokenVerifier.Result.NOT_CONFIGURED);
        lenient().when(cameraTokenVerifier.rejectWhenNotConfigured()).thenReturn(false);

        com.wms.defect.api.dto.DefectImageUploadResultDTO ok =
                new com.wms.defect.api.dto.DefectImageUploadResultDTO();
        ok.setSuccess(true);
        when(defectImageService.upload(any(), any(), any(), any())).thenReturn(ok);

        mockMvc.perform(req()
                        .header("Authorization", "Bearer any")
                        .header("X-Device-Id", "DA4733319"))
                .andExpect(status().isOk());

        verify(defectImageService).upload(any(), any(), any(), any());
    }

    @Test
    @DisplayName("令牌校验通过 → 进入业务处理")
    void validTokenProceeds() throws Exception {
        when_verify(CameraTokenVerifier.Result.OK);

        com.wms.defect.api.dto.DefectImageUploadResultDTO ok =
                new com.wms.defect.api.dto.DefectImageUploadResultDTO();
        ok.setSuccess(true);
        when(defectImageService.upload(any(), any(), any(), any())).thenReturn(ok);

        mockMvc.perform(req()
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Device-Id", "DA4733319"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(defectImageService).upload(any(), any(), any(), any());
    }

    private void when_verify(CameraTokenVerifier.Result result) {
        lenient().when(cameraTokenVerifier.verify(anyString(), anyString())).thenReturn(result);
    }
}
