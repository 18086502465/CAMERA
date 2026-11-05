package com.wms.controller;

import com.wms.defect.app.DefectImageServiceImpl;
import com.wms.defect.infra.repository.DefectImageRepository;
import com.wms.inspection.app.InspectionUploadServiceImpl;
import com.wms.inspection.infra.repository.InspectionUploadRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DDD 重构 P3 批次⑤⑥ 契约测试（缺陷图片 + 检测批次上报）。
 *
 * <p>冻结点：
 * <ul>
 *   <li>缺陷图片<b>历史分页</b>的结构（records/total/page/size）；</li>
 *   <li><b>相机汇总</b>的结构与「最近图片」批量回填（不退回 N+1）；</li>
 *   <li>检测批次上报的<b>幂等</b>分支：同批次重复上报 → accepted + duplicate；</li>
 *   <li>计数不一致时 → 抛 InspectionUploadException(422) 且不写库。</li>
 * </ul>
 *
 * <p>重构期间这些断言失败 = 行为变化，必须停。
 */
@ExtendWith(MockitoExtension.class)
class P3Batch56ContractTest {

    // ---------------- 批次⑤ 缺陷图片 ----------------

    @Mock
    private DefectImageRepository defectImageRepository;

    @InjectMocks
    private DefectImageServiceImpl defectImageService;

    @Test
    @DisplayName("defect-images/history: 分页结构固定（records/total/page/size）")
    void historyPageStructure() {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", Collections.emptyList());
        page.put("total", 0L);
        page.put("page", 1);
        page.put("size", 10);
        when(defectImageRepository.listHistoryPage(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any())).thenReturn(page);

        Map<String, Object> result = defectImageService.listHistoryPage(1, 10,
                null, null, null, null, null, null, null, null, null, null);

        org.junit.jupiter.api.Assertions.assertNotNull(result.get("records"));
        org.junit.jupiter.api.Assertions.assertEquals(0L, result.get("total"));
        org.junit.jupiter.api.Assertions.assertEquals(1, result.get("page"));
        org.junit.jupiter.api.Assertions.assertEquals(10, result.get("size"));
    }

    @Test
    @DisplayName("defect-images/camera-summaries: 返回数组且每项含 latestImages（批量回填，不退回 N+1）")
    void cameraSummariesStructure() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cameraId", "DA9200622");
        summary.put("cameraName", "CV1_Cam1");
        summary.put("imageCount", 100L);
        summary.put("defectCount", 5L);
        when(defectImageRepository.listCameraSummaries(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(Collections.singletonList(summary));
        when(defectImageRepository.listRecentForSummaries(any(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(Collections.emptyMap());

        java.util.List<Map<String, Object>> result =
                defectImageService.listCameraSummaries(5, null, null, null, null, null, null, null, null, null, null);

        org.junit.jupiter.api.Assertions.assertEquals(1, result.size());
        org.junit.jupiter.api.Assertions.assertEquals("DA9200622", result.get(0).get("cameraId"));
        org.junit.jupiter.api.Assertions.assertEquals(100L, result.get(0).get("imageCount"));
        org.junit.jupiter.api.Assertions.assertNotNull(result.get(0).get("latestImages"));
    }

    @Test
    @DisplayName("defect-images/recent: 返回数组")
    void recentStructure() {
        when(defectImageRepository.listRecentImages(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(Collections.emptyList());

        java.util.List<Map<String, Object>> result =
                defectImageService.listRecent(1000, null, null, null, null, null, null, null, null, null, null);

        org.junit.jupiter.api.Assertions.assertNotNull(result);
        org.junit.jupiter.api.Assertions.assertTrue(result.isEmpty());
    }

    // ---------------- 批次⑥ 检测批次上报 ----------------

    @Mock
    private InspectionUploadRepository inspectionUploadRepository;

    @InjectMocks
    private InspectionUploadServiceImpl inspectionUploadService;

    private com.wms.inspection.api.dto.InspectionBatchUploadRequest newRequest(String batchId, long total, long ok, long ng) {
        com.wms.inspection.api.dto.InspectionBatchUploadRequest request =
                new com.wms.inspection.api.dto.InspectionBatchUploadRequest();
        request.setBatchId(batchId);
        request.setCameraCode("ZZTEST-CAM-001");
        request.setSchemaVersion("2.0");
        request.setProductName("ZZTEST产品");
        request.setCounterMode("INCREMENTAL");
        com.wms.inspection.api.dto.InspectionBatchUploadRequest.TimeRange timeRange =
                new com.wms.inspection.api.dto.InspectionBatchUploadRequest.TimeRange();
        timeRange.setStartTime("2026-11-05 09:00:00");
        timeRange.setEndTime("2026-11-05 10:00:00");
        request.setTimeRange(timeRange);
        request.setReportTime("2026-11-05 10:00:00");
        com.wms.inspection.api.dto.InspectionBatchUploadRequest.Summary summary =
                new com.wms.inspection.api.dto.InspectionBatchUploadRequest.Summary();
        summary.setTotalCount(total);
        summary.setOkCount(ok);
        summary.setNgCount(ng);
        request.setSummary(summary);
        request.setDefects(Collections.emptyList());
        return request;
    }

    @Test
    @DisplayName("batch-upload: 计数不一致 → 抛 InspectionUploadException(422) 且不写库")
    void batchUploadCountMismatch() {
        com.wms.inspection.api.dto.InspectionBatchUploadRequest request =
                newRequest("ZZTEST-BATCH-001", 10L, 5L, 3L);

        // ★ 原行为：计数不一致抛 InspectionUploadException（HTTP 422），
        //   且**不写任何库** —— 语义下沉后必须保持。
        org.junit.jupiter.api.Assertions.assertThrows(
                com.wms.inspection.app.InspectionUploadException.class,
                () -> inspectionUploadService.upload(request, "Bearer x", "DEV-1", "ZZTEST-BATCH-001"));

        verify(inspectionUploadRepository, never()).insertBatch(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("batch-upload: 同批次重复上报 → accepted + duplicate，不重复写批次")
    void batchUploadIdempotent() {
        com.wms.inspection.api.dto.InspectionBatchUploadRequest request =
                newRequest("ZZTEST-BATCH-002", 0L, 0L, 0L);

        Map<String, Object> camera = new HashMap<>();
        camera.put("camera_id", 1L);
        camera.put("camera_code", "ZZTEST-CAM-001");
        when(inspectionUploadRepository.findCamera(eq("ZZTEST-CAM-001"), any())).thenReturn(camera);
        Map<String, Object> product = new HashMap<>();
        product.put("product_id", 2L);
        product.put("product_name", "ZZTEST产品");
        when(inspectionUploadRepository.findProduct("ZZTEST产品")).thenReturn(product);
        // 幂等命中：已存在同批次记录
        when(inspectionUploadRepository.countExistingBatch(1L, "ZZTEST-BATCH-002")).thenReturn(1);
        when(inspectionUploadRepository.queryExistingBatchId(1L, "ZZTEST-BATCH-002")).thenReturn(888L);

        com.wms.inspection.api.dto.InspectionBatchUploadResponse resp =
                inspectionUploadService.upload(request, "Bearer x", "DEV-1", "ZZTEST-BATCH-002");

        org.junit.jupiter.api.Assertions.assertTrue(Boolean.TRUE.equals(resp.getAccepted()));
        org.junit.jupiter.api.Assertions.assertTrue(Boolean.TRUE.equals(resp.getDuplicate()));
        org.junit.jupiter.api.Assertions.assertEquals(888L, resp.getInspectionBatchId());

        // 幂等命中时不应再插入新批次
        verify(inspectionUploadRepository, never()).insertBatch(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }
}
