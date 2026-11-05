package com.wms.analytics.app.listener;

import com.wms.analytics.infra.repository.AnomalyAlertRepository;
import com.wms.device.app.event.CameraAlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 相机异常告警监听器（DDD P4 事件化）。
 *
 * <p>device 域心跳处理发现异常时发布 {@link CameraAlertEvent}，本监听器负责落库
 * {@code fact_anomaly_alert}。原逻辑位于 device 域的 insertAnomalyAlert()（P4 前直接
 * 注入 analytics 域 Repository，违反跨域规则），SQL 原样搬运到
 * {@link AnomalyAlertRepository#insertCameraAlert}（本就存在于该 Repository）。
 *
 * <p>行为保持：① 与原实现一致使用**同步** @EventListener（发布线程内执行，无时序变化）；
 * ② 吞异常不阻断主流程（DataAccessException 只记 WARN，与原 catch 语义一致）；
 * ③ 幂等：纯 INSERT，每次心跳告警各插一条（原语义如此，未做去重）。
 */
@Component
public class CameraAlertEventListener {

    private static final Logger log = LoggerFactory.getLogger(CameraAlertEventListener.class);

    @Autowired
    private AnomalyAlertRepository anomalyAlertRepository;

    @EventListener
    public void onCameraAlert(CameraAlertEvent event) {
        try {
            anomalyAlertRepository.insertCameraAlert(
                    event.getLineId(), event.getWorkstationId(),
                    event.getAlertType(), event.getAlertLevel(),
                    event.getAlertTitle(), event.getAlertMessage());
        } catch (DataAccessException e) {
            log.warn("写入相机异常告警失败: type={}, title={}, error={}",
                    event.getAlertType(), event.getAlertTitle(), e.getMessage());
        }
    }
}
