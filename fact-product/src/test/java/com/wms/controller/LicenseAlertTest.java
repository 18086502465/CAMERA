package com.wms.controller;

import com.wms.analytics.app.listener.LicenseAlertListener;
import com.wms.analytics.infra.repository.AnomalyAlertRepository;
import com.wms.shared.common.event.LicenseAlertEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 授权到期告警契约测试（2026-11-23 决策 #7 / LIC-12）。
 *
 * <p>此前授权 EXPIRED 只打 WARN 日志、不写 fact_anomaly_alert —— 夜里过期没人知道。
 * 本测试冻结：状态异常 → 落 LICENSE_EXPIRED 运维告警（HIGH），恢复 → 自动关闭旧告警。
 */
@ExtendWith(MockitoExtension.class)
class LicenseAlertTest {

    @Mock
    private AnomalyAlertRepository anomalyAlertRepository;

    @InjectMocks
    private LicenseAlertListener listener;

    @Test
    @DisplayName("授权到期 → 落 LICENSE_EXPIRED 告警（HIGH，含状态/到期日/处置建议）")
    void expiredRaisesAlert() {
        listener.onLicenseAlert(new LicenseAlertEvent(
                "EXPIRED", "授权已到期，请续期", "2026-11-23", -1L, false));

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> level = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(anomalyAlertRepository).insertCameraAlert(eq(0), eq(0), type.capture(), level.capture(),
                title.capture(), msg.capture());

        assertEquals("LICENSE_EXPIRED", type.getValue());
        assertEquals("HIGH", level.getValue());
        assertTrue(title.getValue().contains("EXPIRED"), "标题应含状态：" + title.getValue());
        assertTrue(msg.getValue().contains("2026-11-23"), "正文应含到期日：" + msg.getValue());
        assertTrue(msg.getValue().contains("续期"), "正文应给出处置建议：" + msg.getValue());
    }

    @Test
    @DisplayName("恢复 → 关闭既有告警，不新增")
    void recoveredResolvesExistingAlert() {
        when(anomalyAlertRepository.findUnresolvedAlertId(0, 0, "LICENSE_EXPIRED")).thenReturn(42L);

        listener.onLicenseAlert(new LicenseAlertEvent("VALID", "授权有效", "2027-11-23", 365L, true));

        verify(anomalyAlertRepository).resolveCameraAlert(eq(42L), eq("AUTO_RECOVER"));
        verify(anomalyAlertRepository, never()).insertCameraAlert(anyInt(), anyInt(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("恢复时无活跃告警 → 不报错、不做任何写操作")
    void recoveredWithNoActiveAlertDoesNothing() {
        when(anomalyAlertRepository.findUnresolvedAlertId(0, 0, "LICENSE_EXPIRED")).thenReturn(null);

        listener.onLicenseAlert(new LicenseAlertEvent("VALID", "授权有效", null, null, true));

        verify(anomalyAlertRepository, never()).resolveCameraAlert(anyLong(), anyString());
        verify(anomalyAlertRepository, never()).insertCameraAlert(eq(0), eq(0), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("LICENSE_EXPIRED 必须登记为运维类告警（否则会被相机告警挤到窗口外）")
    void licenseExpiredIsOpsAlertType() throws Exception {
        Class<?> clazz = Class.forName("com.wms.analytics.api.FactAnomalyAlertController");
        java.lang.reflect.Field f = clazz.getDeclaredField("OPS_ALERT_TYPES");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> ops = (java.util.Set<String>) f.get(null);
        assertTrue(ops.contains("LICENSE_EXPIRED"),
                "OPS_ALERT_TYPES 必须包含 LICENSE_EXPIRED，否则到期告警会被当成业务告警沉底");
    }
}
