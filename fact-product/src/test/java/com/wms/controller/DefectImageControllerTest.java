package com.wms.controller;

import com.wms.dto.DefectImageUploadResultDTO;
import com.wms.service.IDefectImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public API contract test: POST /api/v1/defect-images/upload.
 * This freezes controller HTTP contract without loading Spring context, file system or database.
 */
@ExtendWith(MockitoExtension.class)
class DefectImageControllerTest {

    private static final String URL = "/api/v1/defect-images/upload";

    @Mock
    private IDefectImageService defectImageService;

    @InjectMocks
    private DefectImageController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "maxBatchImages", 100);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("defect image upload accepts multiple files field")
    void uploadSuccessWithFiles() throws Exception {
        DefectImageUploadResultDTO response = new DefectImageUploadResultDTO();
        response.setSuccess(true);
        response.setBatchNo("IMG-BATCH-HZ-001");
        response.setCameraId("CAM-HZ-001");
        response.setCameraName("HZ-Station-A-Front-Camera");
        response.setImageCount(2);
        response.setSuccessCount(2);
        response.setFailCount(0);
        response.setDefectCount(2);
        response.setHighCount(1);
        response.setMediumCount(1);

        when(defectImageService.upload(any(), eq(metadata()), eq("Bearer device-token"), eq("CAM-HZ-001")))
                .thenReturn(response);

        mockMvc.perform(baseMultipart()
                        .file(image("hz-001.jpg"))
                        .file(image("hz-002.jpg"))
                        .param("metadata", metadata()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.imageCount").value(2))
                .andExpect(jsonPath("$.data.defectCount").value(2));
    }

    @Test
    @DisplayName("defect image upload rejects more than 100 images before service layer")
    void uploadRejectsMoreThanOneHundredImages() throws Exception {
        MockMultipartHttpServletRequestBuilder request = baseMultipart();
        request.param("metadata", metadata());
        for (int i = 0; i < 101; i++) {
            request.file(image("hz-over-limit-" + i + ".jpg"));
        }

        mockMvc.perform(request)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));

        verify(defectImageService, never()).upload(any(), any(), any(), any());
    }

    private MockMultipartHttpServletRequestBuilder baseMultipart() {
        MockMultipartHttpServletRequestBuilder request = multipart(URL);
        request.header("Authorization", "Bearer device-token");
        request.header("X-Device-Id", "CAM-HZ-001");
        return request;
    }

    private MockMultipartFile image(String fileName) {
        return new MockMultipartFile("files", fileName, "image/jpeg", new byte[]{1, 2, 3});
    }

    private String metadata() {
        return "{\"cameraId\":\"CAM-HZ-001\",\"cameraName\":\"HZ-Station-A-Front-Camera\",\"uploadTime\":\"2026-06-16 12:00:00\",\"images\":[{\"fileName\":\"hz-001.jpg\",\"captureTime\":\"2026-06-16 11:59:58\",\"defects\":[{\"type\":\"foreign_body\",\"severity\":\"HIGH\",\"x\":10,\"y\":20,\"width\":30,\"height\":40}]}]}";
    }
}