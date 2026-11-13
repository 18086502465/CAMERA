package com.wms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.inspection.api.BatchUploadController;
import com.wms.inspection.api.dto.InspectionBatchUploadRequest;
import com.wms.inspection.api.dto.InspectionBatchUploadResponse;
import com.wms.inspection.app.InspectionUploadException;
import com.wms.inspection.app.InspectionUploadService;
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

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public API contract test: POST /api/v1/camera/inspection/batch-upload.
 * This freezes controller HTTP contract without loading Spring context or database.
 */
@ExtendWith(MockitoExtension.class)
class BatchUploadControllerTest {

    private static final String URL = "/api/v1/camera/inspection/batch-upload";

    @Mock
    private InspectionUploadService inspectionUploadService;

    @InjectMocks
    private BatchUploadController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("batch upload success returns http 200 and code 200")
    void batchUploadSuccess() throws Exception {
        InspectionBatchUploadResponse response = new InspectionBatchUploadResponse();
        response.setAccepted(true);
        response.setDuplicate(false);
        response.setBatchId("BATCH-HZ-001");
        response.setCameraCode("CAM-HZ-001");
        response.setProductName("HZ-Product-A");
        response.setTotalCount(100L);
        response.setOkCount(95L);
        response.setNgCount(5L);
        response.setMappedDefectCount(5L);
        response.setUnmappedDefectCount(0L);

        when(inspectionUploadService.upload(any(InspectionBatchUploadRequest.class),
                eq("Bearer device-token"), eq("CAM-HZ-001"), eq("BATCH-HZ-001")))
                .thenReturn(response);

        mockMvc.perform(post(URL)
                        .header("Authorization", "Bearer device-token")
                        .header("X-Device-Id", "CAM-HZ-001")
                        .header("X-Batch-Id", "BATCH-HZ-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.batchId").value("BATCH-HZ-001"))
                .andExpect(jsonPath("$.data.cameraCode").value("CAM-HZ-001"))
                .andExpect(jsonPath("$.data.totalCount").value(100))
                .andExpect(jsonPath("$.data.ngCount").value(5));
    }

    @Test
    @DisplayName("batch upload validation failure returns http 422 and code 422")
    void batchUploadValidationFailed() throws Exception {
        InspectionBatchUploadResponse response = new InspectionBatchUploadResponse();
        response.setAccepted(false);
        response.setBatchId("BATCH-HZ-002");
        InspectionBatchUploadResponse.UploadIssue issue = new InspectionBatchUploadResponse.UploadIssue();
        issue.setSourceCode("P0_FOREIGN_BODY");
        issue.setIssueType("UNMAPPED_DEFECT_CODE");
        issue.setMessage("defect code is not mapped");
        issue.setSuggestion("configure product-camera-defect mapping first");
        response.setIssues(Collections.singletonList(issue));

        when(inspectionUploadService.upload(any(InspectionBatchUploadRequest.class), any(), any(), any()))
                .thenThrow(new InspectionUploadException(422, "defect code is not mapped", response));

        mockMvc.perform(post(URL)
                        .header("Authorization", "Bearer device-token")
                        .header("X-Device-Id", "CAM-HZ-001")
                        .header("X-Batch-Id", "BATCH-HZ-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value("defect code is not mapped"))
                .andExpect(jsonPath("$.data.accepted").value(false))
                .andExpect(jsonPath("$.data.issues[0].issueType").value("UNMAPPED_DEFECT_CODE"));
    }

    private InspectionBatchUploadRequest validRequest() {
        InspectionBatchUploadRequest request = new InspectionBatchUploadRequest();
        request.setSchemaVersion("2.0");
        request.setBatchId("BATCH-HZ-001");
        request.setRequestId("REQ-HZ-001");
        request.setCameraCode("CAM-HZ-001");
        request.setProductName("HZ-Product-A");
        request.setReportTime("2026-06-16 12:00:00");
        request.setCounterMode("INCREMENTAL");

        InspectionBatchUploadRequest.TimeRange timeRange = new InspectionBatchUploadRequest.TimeRange();
        timeRange.setStartTime("2026-06-16 11:30:00");
        timeRange.setEndTime("2026-06-16 12:00:00");
        request.setTimeRange(timeRange);

        InspectionBatchUploadRequest.Summary summary = new InspectionBatchUploadRequest.Summary();
        summary.setTotalCount(100L);
        summary.setOkCount(95L);
        summary.setNgCount(5L);
        request.setSummary(summary);

        InspectionBatchUploadRequest.DefectItem defect = new InspectionBatchUploadRequest.DefectItem();
        defect.setSourceCode("P0_FOREIGN_BODY");
        defect.setCount(5L);
        request.setDefects(Collections.singletonList(defect));
        return request;
    }
}