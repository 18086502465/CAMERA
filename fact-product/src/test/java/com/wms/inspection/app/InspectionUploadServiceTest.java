package com.wms.inspection.app;

import com.wms.inspection.api.dto.InspectionBatchUploadRequest;
import com.wms.inspection.api.dto.InspectionBatchUploadResponse;
import com.wms.inspection.infra.repository.InspectionUploadRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 冻结契约测试：批量上报（{@code POST /api/v1/camera/inspection/batch-upload}）的
 * <b>Service 层</b>校验语义。
 *
 * <p>为什么需要这个测试：既有的 {@code controller/BatchUploadControllerTest} 把 service
 * mock 掉了，只验证 HTTP 包装（200/422 与响应结构）。契约的真身——20 个 {@code fail422}
 * 分支、计数守恒、幂等判定——完全没有断言。该测试构造的请求甚至用了
 * {@code schemaVersion="1.0"}（Service 要求 {@code "2.0"}）却依然通过，正好证明
 * 「mock 掉被测对象」的测试保护不了契约。</p>
 *
 * <p>本测试直接调用 Service 实现，只 mock 仓储层，因此校验逻辑的任何放宽/收紧/改码
 * 都会在这里变红。冻结契约的核心声明是「URL / Header 名 / 已约定 JSON 字段不得删改」，
 * {@link DtoFieldContract} 用反射把这句话变成可执行断言。</p>
 */
@ExtendWith(MockitoExtension.class)
class InspectionUploadServiceTest {

    @Mock
    private InspectionUploadRepository repository;

    @InjectMocks
    private InspectionUploadServiceImpl service;

    private static final String AUTH = "Bearer device-token";
    private static final String PLAIN_TOKEN = "device-token";
    private static final String DEVICE_ID = "CAM-ZK23-01";
    private static final String BATCH_ID = "BATCH-20261113-001";

    // ---------------------------------------------------------------------
    // 基础校验：全部在触碰仓储之前失败，无需任何 stub
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("请求级校验（validateBasic）")
    class BasicValidation {

        @Test
        @DisplayName("缺 Authorization → TOKEN_REQUIRED")
        void missingAuthorization() {
            assertIssue(expectFail(validRequest(), null, DEVICE_ID, BATCH_ID), "TOKEN_REQUIRED");
        }

        @Test
        @DisplayName("Authorization 只有 Bearer 前缀、令牌为空 → TOKEN_REQUIRED")
        void blankBearerToken() {
            assertIssue(expectFail(validRequest(), "Bearer   ", DEVICE_ID, BATCH_ID), "TOKEN_REQUIRED");
        }

        @Test
        @DisplayName("缺 X-Device-Id → DEVICE_ID_REQUIRED")
        void missingDeviceId() {
            assertIssue(expectFail(validRequest(), AUTH, "  ", BATCH_ID), "DEVICE_ID_REQUIRED");
        }

        @Test
        @DisplayName("请求体为 null → INVALID_BODY")
        void nullBody() {
            assertIssue(expectFail(null, AUTH, DEVICE_ID, BATCH_ID), "INVALID_BODY");
        }

        @Test
        @DisplayName("schemaVersion=1.0 → INVALID_SCHEMA_VERSION（冻结契约强制 2.0）")
        void unsupportedSchemaVersion() {
            InspectionBatchUploadRequest req = validRequest();
            req.setSchemaVersion("1.0");
            assertIssue(expectFail(req, AUTH, DEVICE_ID, BATCH_ID), "INVALID_SCHEMA_VERSION");
        }

        @Test
        @DisplayName("body 与 header 都没有 batchId → BATCH_ID_REQUIRED")
        void missingBatchId() {
            InspectionBatchUploadRequest req = validRequest();
            req.setBatchId(null);
            assertIssue(expectFail(req, AUTH, DEVICE_ID, null), "BATCH_ID_REQUIRED");
        }

        @Test
        @DisplayName("X-Batch-Id 兜底生效：body 不填 batchId 时取 header")
        void batchIdFallsBackToHeader() {
            InspectionBatchUploadRequest req = validRequest();
            req.setBatchId(null);
            // 显式 stub 相机不存在。注意：Mockito 对 Map 返回**空 Map 而非 null**
            // （ReturnsEmptyValues），若依赖「未 stub 即 null」会误判成"流程走通"。
            when(repository.findCamera(anyString(), any())).thenReturn(null);
            assertIssue(expectFail(req, AUTH, DEVICE_ID, BATCH_ID), "CAMERA_NOT_FOUND");
        }

        @Test
        @DisplayName("缺 cameraCode → CAMERA_CODE_REQUIRED")
        void missingCameraCode() {
            InspectionBatchUploadRequest req = validRequest();
            req.setCameraCode(" ");
            assertIssue(expectFail(req, AUTH, DEVICE_ID, BATCH_ID), "CAMERA_CODE_REQUIRED");
        }

        @Test
        @DisplayName("缺 productName → PRODUCT_NAME_REQUIRED")
        void missingProductName() {
            InspectionBatchUploadRequest req = validRequest();
            req.setProductName(null);
            assertIssue(expectFail(req, AUTH, DEVICE_ID, BATCH_ID), "PRODUCT_NAME_REQUIRED");
        }

        @Test
        @DisplayName("counterMode 非 INCREMENTAL → UNSUPPORTED_COUNTER_MODE")
        void unsupportedCounterMode() {
            InspectionBatchUploadRequest req = validRequest();
            req.setCounterMode("CUMULATIVE");
            assertIssue(expectFail(req, AUTH, DEVICE_ID, BATCH_ID), "UNSUPPORTED_COUNTER_MODE");
        }

        @Test
        @DisplayName("counterMode 大小写不敏感：incremental 放行")
        void counterModeCaseInsensitive() {
            InspectionBatchUploadRequest req = validRequest();
            req.setCounterMode("incremental");
            when(repository.findCamera(anyString(), any())).thenReturn(null);
            assertIssue(expectFail(req, AUTH, DEVICE_ID, BATCH_ID), "CAMERA_NOT_FOUND");
        }

        @Test
        @DisplayName("缺 summary → SUMMARY_REQUIRED")
        void missingSummary() {
            InspectionBatchUploadRequest req = validRequest();
            req.setSummary(null);
            assertIssue(expectFail(req, AUTH, DEVICE_ID, BATCH_ID), "SUMMARY_REQUIRED");
        }

        @Test
        @DisplayName("缺 timeRange → TIME_RANGE_REQUIRED")
        void missingTimeRange() {
            InspectionBatchUploadRequest req = validRequest();
            req.setTimeRange(null);
            assertIssue(expectFail(req, AUTH, DEVICE_ID, BATCH_ID), "TIME_RANGE_REQUIRED");
        }

        @Test
        @DisplayName("timeRange 缺 endTime → TIME_RANGE_REQUIRED")
        void timeRangeMissingEnd() {
            InspectionBatchUploadRequest req = validRequest();
            req.getTimeRange().setEndTime("");
            assertIssue(expectFail(req, AUTH, DEVICE_ID, BATCH_ID), "TIME_RANGE_REQUIRED");
        }
    }

    // ---------------------------------------------------------------------
    // 业务校验：相机 / 令牌 / 产品 / 计数守恒 / 映射
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("业务校验")
    class BusinessValidation {

        @Test
        @DisplayName("相机未建档 → CAMERA_NOT_FOUND")
        void cameraNotFound() {
            when(repository.findCamera(anyString(), any())).thenReturn(null);
            assertIssue(expectFail(validRequest(), AUTH, DEVICE_ID, BATCH_ID), "CAMERA_NOT_FOUND");
        }

        @Test
        @DisplayName("令牌与配置不符 → TOKEN_INVALID")
        void tokenInvalid() {
            stubCamera();
            when(repository.listCameraTokens(any()))
                    .thenReturn(Collections.singletonList(row("token_hash", "another-token")));
            assertIssue(expectFail(validRequest(), AUTH, DEVICE_ID, BATCH_ID), "TOKEN_INVALID");
        }

        @Test
        @DisplayName("严格模式下相机未配令牌 → TOKEN_NOT_CONFIGURED")
        void tokenNotConfiguredInStrictMode() {
            ReflectionTestUtils.setField(service, "tokenStrictMode", true);
            stubCamera();
            when(repository.listCameraTokens(any())).thenReturn(Collections.emptyList());
            assertIssue(expectFail(validRequest(), AUTH, DEVICE_ID, BATCH_ID), "TOKEN_NOT_CONFIGURED");
        }

        @Test
        @DisplayName("兼容模式（默认）下相机未配令牌 → 放行，不拒绝")
        void tokenNotConfiguredInCompatMode() {
            stubCamera();
            when(repository.listCameraTokens(any())).thenReturn(Collections.emptyList());
            when(repository.findProduct(anyString())).thenReturn(null);
            // 不因令牌拒绝，而是继续走到产品校验
            assertIssue(expectFail(validRequest(), AUTH, DEVICE_ID, BATCH_ID), "PRODUCT_NOT_FOUND");
        }

        @Test
        @DisplayName("产品未配置/停用 → PRODUCT_NOT_FOUND")
        void productNotFound() {
            stubCamera();
            stubTokenOk();
            when(repository.findProduct(anyString())).thenReturn(null);
            assertIssue(expectFail(validRequest(), AUTH, DEVICE_ID, BATCH_ID), "PRODUCT_NOT_FOUND");
        }

        @Test
        @DisplayName("total != ok + ng → COUNT_MISMATCH")
        void totalMismatch() {
            stubCamera();
            stubTokenOk();
            stubProduct();
            InspectionBatchUploadRequest req = validRequest();
            req.getSummary().setTotalCount(101L);
            assertIssue(expectFail(req, AUTH, DEVICE_ID, BATCH_ID), "COUNT_MISMATCH");
        }

        @Test
        @DisplayName("ng != Σdefects[].count → COUNT_MISMATCH")
        void ngMismatch() {
            stubCamera();
            stubTokenOk();
            stubProduct();
            InspectionBatchUploadRequest req = validRequest();
            req.getDefects().get(0).setCount(4L);   // ng=5 但缺陷和为 4
            assertIssue(expectFail(req, AUTH, DEVICE_ID, BATCH_ID), "COUNT_MISMATCH");
        }

        @Test
        @DisplayName("缺陷数量为负 → INVALID_DEFECT_COUNT（带 sourceCode）")
        void negativeDefectCount() {
            stubCamera();
            stubTokenOk();
            stubProduct();
            when(repository.countExistingBatch(any(), anyString())).thenReturn(0);
            InspectionBatchUploadRequest req = validRequest();
            req.getDefects().get(0).setCount(-1L);
            req.getSummary().setNgCount(-1L);       // 让计数守恒先通过
            req.getSummary().setTotalCount(99L);
            req.getSummary().setOkCount(100L);

            InspectionUploadException ex = expectFail(req, AUTH, DEVICE_ID, BATCH_ID);
            assertIssue(ex, "INVALID_DEFECT_COUNT");
            assertEquals("P0_FOREIGN_BODY", ex.getResponse().getIssues().get(0).getSourceCode());
        }

        @Test
        @DisplayName("sourceCode 无映射 → UNMAPPED（带 sourceCode）")
        void unmappedSourceCode() {
            stubCamera();
            stubTokenOk();
            stubProduct();
            when(repository.countExistingBatch(any(), anyString())).thenReturn(0);
            when(repository.findMapping(any(), any(), anyString(), any())).thenReturn(null);

            InspectionUploadException ex = expectFail(validRequest(), AUTH, DEVICE_ID, BATCH_ID);
            assertIssue(ex, "UNMAPPED");
            assertEquals("P0_FOREIGN_BODY", ex.getResponse().getIssues().get(0).getSourceCode());
        }

        @Test
        @DisplayName("reportTime 格式非法 → INVALID_TIME_FORMAT")
        void invalidReportTime() {
            InspectionBatchUploadRequest req = validRequest();
            req.setReportTime("2026/06/16 12:00");
            assertIssue(expectFail(req, AUTH, DEVICE_ID, BATCH_ID), "INVALID_TIME_FORMAT");
        }
    }

    // ---------------------------------------------------------------------
    // 幂等与成功路径
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("幂等与成功路径")
    class IdempotencyAndSuccess {

        @Test
        @DisplayName("重复 batchId → accepted=true, duplicate=true，且不再写库")
        void duplicateBatchIsIdempotent() {
            stubCamera();
            stubTokenOk();
            stubProduct();
            when(repository.countExistingBatch(any(), anyString())).thenReturn(1);
            when(repository.queryExistingBatchId(any(), anyString())).thenReturn(999L);

            InspectionBatchUploadResponse resp = service.upload(validRequest(), AUTH, DEVICE_ID, BATCH_ID);

            assertTrue(resp.getAccepted());
            assertTrue(resp.getDuplicate());
            assertEquals(999L, resp.getInspectionBatchId());
            verify(repository, never()).insertBatch(any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(),
                    anyLong(), anyLong(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("正常上报 → accepted=true, duplicate=false，缺陷被映射并写库")
        void successPath() {
            stubCamera();
            stubTokenOk();
            stubProduct();
            when(repository.countExistingBatch(any(), anyString())).thenReturn(0);
            when(repository.findMapping(any(), any(), anyString(), any())).thenReturn(mapping());
            when(repository.insertBatch(any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(),
                    anyLong(), anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(123L);

            InspectionBatchUploadResponse resp = service.upload(validRequest(), AUTH, DEVICE_ID, BATCH_ID);

            assertTrue(resp.getAccepted());
            assertFalse(resp.getDuplicate());
            assertEquals(123L, resp.getInspectionBatchId());
            assertEquals("CAM-ZK23-01", resp.getCameraCode());
            assertEquals(100L, resp.getTotalCount());
            assertEquals(95L, resp.getOkCount());
            assertEquals(5L, resp.getNgCount());
            assertEquals(5L, resp.getMappedDefectCount());
            assertEquals(0L, resp.getUnmappedDefectCount());
            assertEquals(1, resp.getMappedDefects().size());
            assertEquals("P0_FOREIGN_BODY", resp.getMappedDefects().get(0).getSourceCode());
            assertEquals(5L, resp.getMappedDefects().get(0).getCount());
        }
    }

    // ---------------------------------------------------------------------
    // DTO 字段级契约：冻结契约「已约定 JSON 字段不得删改」的可执行版本
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("DTO 字段冻结契约")
    class DtoFieldContract {

        @Test
        @DisplayName("InspectionBatchUploadRequest 字段集合固定")
        void requestFieldsFrozen() {
            assertFieldsFrozen(InspectionBatchUploadRequest.class,
                    "schemaVersion", "batchId", "requestId", "cameraCode", "productName",
                    "reportTime", "timeRange", "counterMode", "summary", "defects");
        }

        @Test
        @DisplayName("TimeRange 字段集合固定")
        void timeRangeFieldsFrozen() {
            assertFieldsFrozen(InspectionBatchUploadRequest.TimeRange.class, "startTime", "endTime");
        }

        @Test
        @DisplayName("Summary 字段集合固定")
        void summaryFieldsFrozen() {
            assertFieldsFrozen(InspectionBatchUploadRequest.Summary.class,
                    "totalCount", "okCount", "ngCount");
        }

        @Test
        @DisplayName("DefectItem 字段集合固定")
        void defectItemFieldsFrozen() {
            assertFieldsFrozen(InspectionBatchUploadRequest.DefectItem.class, "sourceCode", "count");
        }
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    /** 调用 service 并断言抛出 422 契约异常，返回该异常供进一步断言。 */
    private InspectionUploadException expectFail(InspectionBatchUploadRequest req,
                                                 String auth, String deviceId, String batchId) {
        InspectionUploadException ex = assertThrows(InspectionUploadException.class,
                () -> service.upload(req, auth, deviceId, batchId));
        assertEquals(422, ex.getHttpStatus(), "冻结契约：校验失败一律 HTTP 422");
        return ex;
    }

    private void assertIssue(InspectionUploadException ex, String expectedIssueType) {
        assertNotNull(ex.getResponse(), "异常必须携带响应体（设备端按 body 判结果）");
        assertFalse(ex.getResponse().getIssues().isEmpty(), "异常必须带 issue 明细");
        assertEquals(expectedIssueType, ex.getResponse().getIssues().get(0).getIssueType());
        assertFalse(ex.getResponse().getAccepted(), "失败响应 accepted 必须为 false");
    }

    private void assertFieldsFrozen(Class<?> type, String... expected) {
        Set<String> actual = new TreeSet<>();
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            actual.add(f.getName());
        }
        Set<String> expectedSet = new TreeSet<>(Arrays.asList(expected));
        assertEquals(expectedSet, actual,
                "冻结契约：字段名不得删改（新增字段需同步更新本测试与《相机设备接口协议》§五）");
    }

    private void stubCamera() {
        when(repository.findCamera(anyString(), any())).thenReturn(new HashMap<String, Object>() {{
            put("camera_id", 7L);
            put("camera_code", "CAM-ZK23-01");
            put("camera_name", "ZK23-相机01");
            put("factory_id", 1L);
            put("line_id", 2L);
            put("workstation_id", 3L);
        }});
    }

    private void stubTokenOk() {
        when(repository.listCameraTokens(any()))
                .thenReturn(Collections.singletonList(row("token_hash", PLAIN_TOKEN)));
    }

    private void stubProduct() {
        when(repository.findProduct(anyString())).thenReturn(new HashMap<String, Object>() {{
            put("product_id", 11L);
            put("product_name", "HZ-Product-A");
        }});
    }

    private Map<String, Object> mapping() {
        return new HashMap<String, Object>() {{
            put("mapping_id", 21L);
            put("defect_type_id", 31L);
            put("defect_code", "FOREIGN_BODY");
            put("defect_name", "异物");
            put("severity", "MAJOR");
        }};
    }

    private Map<String, Object> row(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    private InspectionBatchUploadRequest validRequest() {
        InspectionBatchUploadRequest req = new InspectionBatchUploadRequest();
        req.setSchemaVersion("2.0");
        req.setBatchId(BATCH_ID);
        req.setRequestId("REQ-001");
        req.setCameraCode("CAM-ZK23-01");
        req.setProductName("HZ-Product-A");
        req.setReportTime("2026-06-16 12:00:00");
        req.setCounterMode("INCREMENTAL");

        InspectionBatchUploadRequest.TimeRange tr = new InspectionBatchUploadRequest.TimeRange();
        tr.setStartTime("2026-06-16 11:30:00");
        tr.setEndTime("2026-06-16 12:00:00");
        req.setTimeRange(tr);

        InspectionBatchUploadRequest.Summary sum = new InspectionBatchUploadRequest.Summary();
        sum.setTotalCount(100L);
        sum.setOkCount(95L);
        sum.setNgCount(5L);
        req.setSummary(sum);

        InspectionBatchUploadRequest.DefectItem item = new InspectionBatchUploadRequest.DefectItem();
        item.setSourceCode("P0_FOREIGN_BODY");
        item.setCount(5L);
        req.setDefects(new ArrayList<>(Collections.singletonList(item)));
        return req;
    }
}
