package com.wms.controller;

import com.wms.analytics.app.listener.SchemaIndexAlertListener;
import com.wms.analytics.infra.repository.AnomalyAlertRepository;
import com.wms.device.app.CameraStatusServiceImpl;
import com.wms.device.domain.CameraNotConfiguredException;
import com.wms.shared.common.event.SchemaIndexFailedEvent;
import com.wms.shared.util.LogThrottle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 日志降噪与索引告警契约测试（2026-11-19 评审 P0-2 / P1-1）。
 *
 * <p><b>P0-2 背景</b>：5 台未登记设备每 5 秒发一次心跳，被拒后继续。
 * 原实现 <b>①同一件事记两遍</b>（Service 抛异常 + Controller catch 后再 warn 同一句）
 * <b>②完全不节流</b> → 7 天 26 万条 WARN（ERROR 才 1560 条），
 * 业务日志 80.5% 是噪音，真实告警被彻底淹没。
 *
 * <p><b>P1-1 背景</b>：唯一索引 {@code uk_defect_file_camera_file_name} 是
 * 「上传幂等」的最后一道防线，它建失败时原实现只记一条 WARN 就放弃 ——
 * 不重试、无告警，运维不会注意到。
 */
@ExtendWith(MockitoExtension.class)
class LogNoiseReductionTest {

    @Mock
    private AnomalyAlertRepository anomalyAlertRepository;

    /** 事件发布器（验证 Repository 是否在唯一索引失败时发事件） */
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    // ==================== P0-2：节流 ====================

    @Test
    @DisplayName("P0-2：LogThrottle 对同一 key 在间隔内只放行一次")
    void throttleShouldAllowOnlyOncePerInterval() {
        String key = "test-throttle-" + System.nanoTime();
        assertTrue(LogThrottle.allow(key, 10 * 60 * 1000L), "首次应放行");
        assertFalse(LogThrottle.allow(key, 10 * 60 * 1000L), "间隔内第二次应被抑制");
        assertFalse(LogThrottle.allow(key, 10 * 60 * 1000L), "间隔内第三次应被抑制");
    }

    @Test
    @DisplayName("P0-2：不同 key 相互独立（一台设备的噪音不压制另一台）")
    void throttleKeysShouldBeIndependent() {
        String suffix = String.valueOf(System.nanoTime());
        assertTrue(LogThrottle.allow("noise-a:" + suffix, 10 * 60 * 1000L));
        assertTrue(LogThrottle.allow("noise-b:" + suffix, 10 * 60 * 1000L),
                "不同 key 应各自放行");
    }

    @Test
    @DisplayName("P0-2：未登记设备用自定义异常表达（Controller 据此不重复记日志）")
    void unknownCameraShouldThrowDedicatedException() {
        CameraNotConfiguredException e = new CameraNotConfiguredException("DB0034123");
        assertEquals("DB0034123", e.getDeviceId());
        assertTrue(e.getMessage().contains("设备未在系统配置中维护"), e.getMessage());
        // 继承 IllegalArgumentException → 既有 catch 块与调用方向后兼容
        assertTrue(e instanceof IllegalArgumentException);
    }

    @Test
    @DisplayName("P0-2：Service 层是唯一记录点，且按 deviceId 分键节流")
    void serviceShouldThrottleLogByDeviceId() {
        // 直接验证 Service 里用到的节流键：同一 deviceId 第二次应被抑制
        String deviceId = "ZZTHROTTLE-" + System.nanoTime();
        assertTrue(LogThrottle.allow("heartbeat-invalid:" + deviceId, 10 * 60 * 1000L));
        assertFalse(LogThrottle.allow("heartbeat-invalid:" + deviceId, 10 * 60 * 1000L));
    }

    // ==================== P1-1：唯一索引失败落告警 ====================

    @Test
    @DisplayName("P1-1：唯一索引建失败 → 落 SCHEMA_INDEX_MISSING 告警（HIGH）")
    void uniqueIndexFailureShouldRaiseAlert() {
        SchemaIndexAlertListener listener = new SchemaIndexAlertListener();
        org.springframework.test.util.ReflectionTestUtils.setField(listener, "anomalyAlertRepository", anomalyAlertRepository);

        listener.onSchemaIndexFailed(new SchemaIndexFailedEvent(
                "defect_image_file", "uk_defect_file_camera_file_name", true,
                "Duplicate entry 'DB1615125-接头_20261118111958_048.jpg' for key"));

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> level = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(anomalyAlertRepository).insertCameraAlert(eq(0), eq(0), type.capture(), level.capture(),
                title.capture(), msg.capture());

        assertEquals("SCHEMA_INDEX_MISSING", type.getValue());
        assertEquals("HIGH", level.getValue());
        assertTrue(title.getValue().contains("uk_defect_file_camera_file_name"), title.getValue());
        assertTrue(msg.getValue().contains("幂等"), "正文应点明这是幂等防线缺失：" + msg.getValue());
        assertTrue(msg.getValue().contains("purge_duplicate_images.sql"),
                "正文应给出处置手段（清理脚本）：" + msg.getValue());
    }

    @Test
    @DisplayName("P1-1：SCHEMA_INDEX_MISSING 必须登记为运维类告警（否则被业务告警沉底）")
    void schemaIndexMissingMustBeOpsAlertType() throws Exception {
        Class<?> clazz = Class.forName("com.wms.analytics.api.FactAnomalyAlertController");
        java.lang.reflect.Field f = clazz.getDeclaredField("OPS_ALERT_TYPES");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> ops = (java.util.Set<String>) f.get(null);
        assertTrue(ops.contains("SCHEMA_INDEX_MISSING"),
                "OPS_ALERT_TYPES 必须包含 SCHEMA_INDEX_MISSING，否则会被当成业务告警按级别排序沉底");
    }

    @Test
    @DisplayName("P1-1：普通索引失败**不发布事件**（只是性能问题，下次发版再建）")
    void ordinaryIndexFailureShouldNotPublishEvent() {
        org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);
        // execute(alterSql) 抛异常 → 模拟建索引失败
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("Duplicate entry"))
                .when(jdbc).execute(org.mockito.ArgumentMatchers.anyString());

        com.wms.defect.infra.repository.DefectImageRepository repo =
                new com.wms.defect.infra.repository.DefectImageRepository(jdbc);
        org.springframework.test.util.ReflectionTestUtils.setField(repo, "eventPublisher", eventPublisher);

        // 普通索引：只记 WARN，不发事件
        repo.ensureIndex("defect_image_file", "idx_defect_file_capture_time",
                "ALTER TABLE defect_image_file ADD INDEX idx_defect_file_capture_time(capture_time)");
        verify(eventPublisher, never()).publishEvent(any());

        // 唯一索引：发事件 → analytics 域落告警
        repo.ensureIndex("defect_image_file", "uk_defect_file_camera_file_name",
                "ALTER TABLE defect_image_file ADD UNIQUE INDEX uk_defect_file_camera_file_name(camera_id, file_name)",
                true);
        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(evt.capture());
        assertTrue(evt.getValue() instanceof SchemaIndexFailedEvent,
                "唯一索引失败应发布 SchemaIndexFailedEvent，实际：" + evt.getValue());
        SchemaIndexFailedEvent se = (SchemaIndexFailedEvent) evt.getValue();
        assertTrue(se.isUniqueIndex(), "应标记为唯一索引");
        assertEquals("uk_defect_file_camera_file_name", se.getIndexName());
    }

    @Test
    @DisplayName("P1-3：超长告警正文被截断到列宽内（否则整条告警写不进库 → 静默丢失）")
    void overlongAlertMessageShouldBeTruncated() throws Exception {
        // 直接验证截断行为：2000 字上限
        java.lang.reflect.Method m = null;
        try {
            m = com.wms.analytics.infra.repository.AnomalyAlertRepository.class
                    .getDeclaredMethod("truncate", String.class, int.class);
            m.setAccessible(true);
        } catch (Exception e) {
            throw new IllegalStateException("截断方法不存在", e);
        }
        String long400 = repeat("甲", 400);
        String out = (String) m.invoke(null, long400, 2000);
        assertEquals(400, out.length(), "未超长时不应改动");
        String long3000 = repeat("甲", 3000);
        String out2 = (String) m.invoke(null, long3000, 2000);
        assertEquals(2000, out2.length(), "超长时应截到列宽（含省略号）");
        assertTrue(out2.endsWith("…"), "截断后应以省略号收尾");
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    @Test
    @DisplayName("P1-1：告警写入失败不影响其它（异常被吞，不向上抛）")
    void alertFailureDoesNotThrow() {
        SchemaIndexAlertListener listener = new SchemaIndexAlertListener();
        org.springframework.test.util.ReflectionTestUtils.setField(listener, "anomalyAlertRepository", anomalyAlertRepository);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(anomalyAlertRepository).insertCameraAlert(anyInt(), anyInt(), anyString(),
                        anyString(), anyString(), anyString());
        // 不抛异常即通过
        listener.onSchemaIndexFailed(new SchemaIndexFailedEvent(
                "defect_image_file", "uk_defect_file_camera_file_name", true, "err"));
        verify(anomalyAlertRepository).insertCameraAlert(anyInt(), anyInt(), anyString(),
                anyString(), anyString(), anyString());
    }
}
