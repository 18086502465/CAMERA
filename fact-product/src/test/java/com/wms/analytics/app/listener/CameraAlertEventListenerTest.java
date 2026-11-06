package com.wms.analytics.app.listener;

import com.wms.device.app.event.CameraAlertEvent;
import com.wms.analytics.infra.repository.AnomalyAlertRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DDD P4 事件化契约测试：device 域发布告警事件 → analytics 监听器落库。
 * 关键语义：① 落库参数与原 insertAnomalyAlert 一一对应；
 * ② 落库失败（DataAccessException）被吞 —— 事件化后告警失败不阻断心跳主流程。
 */
@ExtendWith(MockitoExtension.class)
class CameraAlertEventListenerTest {

    @Mock
    private AnomalyAlertRepository anomalyAlertRepository;

    @InjectMocks
    private CameraAlertEventListener listener;

    @Test
    @DisplayName("监听器收到事件 → 按原参数落库 fact_anomaly_alert")
    void eventTriggersInsert() {
        listener.onCameraAlert(new CameraAlertEvent(3, 24, "CAMERA_FAULT", "CRITICAL",
                "相机故障", "相机 CV1_Cam1(DA8119489) 上报故障状态"));

        verify(anomalyAlertRepository).insertCameraAlert(
                eq(3), eq(24), eq("CAMERA_FAULT"), eq("CRITICAL"),
                eq("相机故障"), eq("相机 CV1_Cam1(DA8119489) 上报故障状态"));
    }

    @Test
    @DisplayName("落库失败被吞（不阻断心跳主流程）—— 原行为保持")
    void insertFailureSwallowed() {
        doThrow(new DataAccessException("db down") {}).when(anomalyAlertRepository)
                .insertCameraAlert(any(), any(), any(), any(), any(), any());

        listener.onCameraAlert(new CameraAlertEvent(3, 24, "CPU_HIGH", "WARNING",
                "相机CPU过高", "94%"));

        verify(anomalyAlertRepository).insertCameraAlert(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("v3 H-4：恢复事件不新增行，只把对应告警置为已恢复")
    void resolvedEventUpdatesInsteadOfInsert() {
        listener.onCameraAlert(CameraAlertEvent.resolved(3, 24, "CAMERA_FAULT", "相机故障", 777L));

        verify(anomalyAlertRepository).resolveCameraAlert(eq(777L), eq("AUTO_RECOVER"));
        verify(anomalyAlertRepository, never()).insertCameraAlert(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("v3 H-4：新建告警后把 alert_id 回填到事件（续报/恢复才能定位）")
    void alertIdIsBackFilled() {
        when(anomalyAlertRepository.insertCameraAlert(any(), any(), any(), any(), any(), any()))
                .thenReturn(12345L);
        CameraAlertEvent event = new CameraAlertEvent(3, 24, "TEMPERATURE_HIGH", "WARNING",
                "相机温度过高", "82℃");

        listener.onCameraAlert(event);

        org.junit.jupiter.api.Assertions.assertEquals(12345L, event.getAlertId());
    }
}
