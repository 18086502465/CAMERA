package com.wms.controller;

import com.wms.analytics.app.listener.UploadFailedAlertListener;
import com.wms.analytics.infra.repository.AnomalyAlertRepository;
import com.wms.shared.common.event.UploadFailedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 设备上传失败告警契约测试（2026-11-18 评审 M-04）。
 *
 * <p>背景：现场近 24h 有 39 条 {@code Failed to parse multipart servlet request}
 * + 19 条 {@code Broken pipe}，全部只进了 journald —— 失败的那张图
 * <b>既没入库也没告警</b>，设备端若不重试就永久丢失，现场完全无感知。
 *
 * <p>本测试冻结：multipart 失败 → 落 {@code UPLOAD_FAILED} 告警（运维类）。
 */
@ExtendWith(MockitoExtension.class)
class UploadFailedAlertTest {

    @Mock
    private AnomalyAlertRepository anomalyAlertRepository;

    @InjectMocks
    private UploadFailedAlertListener listener;

    @Test
    @DisplayName("上传失败 → 落 UPLOAD_FAILED 告警（HIGH，含设备号与处置建议）")
    void uploadFailureRaisesAlert() {
        listener.onUploadFailed(new UploadFailedEvent("DA8119489",
                "Processing of multipart/form-data request failed; nested exception is java.net.SocketTimeoutException",
                "MultipartException"));

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> level = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(anomalyAlertRepository).insertCameraAlert(eq(0), eq(0), type.capture(), level.capture(),
                title.capture(), msg.capture());

        assertEquals("UPLOAD_FAILED", type.getValue());
        assertEquals("HIGH", level.getValue());
        assertTrue(title.getValue().contains("DA8119489"), "告警标题应含设备号：" + title.getValue());
        assertTrue(msg.getValue().contains("SocketTimeoutException"), "告警正文应含失败原因：" + msg.getValue());
        assertTrue(msg.getValue().contains("重传"), "告警正文应给出处置建议：" + msg.getValue());
    }

    @Test
    @DisplayName("设备号缺失时不报错，告警标题标注未知设备")
    void missingDeviceIdStillAlerts() {
        listener.onUploadFailed(new UploadFailedEvent(null, "broken pipe", "IOException"));

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(anomalyAlertRepository).insertCameraAlert(eq(0), eq(0), eq("UPLOAD_FAILED"), eq("HIGH"),
                title.capture(), anyString());
        assertTrue(title.getValue().contains("未知设备"), title.getValue());
    }

    @Test
    @DisplayName("UPLOAD_FAILED 必须登记为运维类告警（否则会被业务告警挤到窗口外）")
    void uploadFailedIsOpsAlertType() throws Exception {
        // 反射读取 FactAnomalyAlertController.OPS_ALERT_TYPES
        Class<?> clazz = Class.forName("com.wms.analytics.api.FactAnomalyAlertController");
        java.lang.reflect.Field f = clazz.getDeclaredField("OPS_ALERT_TYPES");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> ops = (java.util.Set<String>) f.get(null);
        assertTrue(ops.contains("UPLOAD_FAILED"),
                "OPS_ALERT_TYPES 必须包含 UPLOAD_FAILED，否则该告警会被当成业务告警按级别排序沉底");
    }

    @Test
    @DisplayName("告警写入失败不影响其它（异常被吞，不向上抛）")
    void alertFailureDoesNotThrow() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(anomalyAlertRepository).insertCameraAlert(anyInt(), anyInt(), anyString(),
                        anyString(), anyString(), anyString());
        // 不抛异常即通过
        listener.onUploadFailed(new UploadFailedEvent("DA1", "reason", "MultipartException"));
        verify(anomalyAlertRepository).insertCameraAlert(anyInt(), anyInt(), anyString(),
                anyString(), anyString(), anyString());
    }
}
