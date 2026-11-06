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
 * ② 吞异常不阻断主流程（DataAccessException 只记 WARN，与原 catch 语义一致）。
 *
 * <p><b>v3 H-4（2026-11-06）起不再是"每次心跳各插一条"</b>：
 * 去重与节流已上移到 device 域的 {@code CameraAlertThrottle}（它需要相机维度的状态，
 * 而本表没有 camera_code 列，只能进程内做）。本监听器现在承担两件事：
 * <ul>
 *   <li>新建告警：插入后把 {@code alert_id} 回填到事件上（同步执行保证发布方读得到），
 *       供节流器下次续报/恢复时定位；</li>
 *   <li>恢复事件：不再插入新行，直接把对应告警置为已解决。</li>
 * </ul>
 */
@Component
public class CameraAlertEventListener {

    private static final Logger log = LoggerFactory.getLogger(CameraAlertEventListener.class);

    @Autowired
    private AnomalyAlertRepository anomalyAlertRepository;

    @EventListener
    public void onCameraAlert(CameraAlertEvent event) {
        try {
            if (event.isResolved()) {
                int rows = anomalyAlertRepository.resolveCameraAlert(event.getAlertId(), "AUTO_RECOVER");
                if (rows == 0) {
                    log.debug("告警 {} 已处于关闭状态（并发/重启导致），忽略恢复事件", event.getAlertId());
                }
                return;
            }
            Long alertId = anomalyAlertRepository.insertCameraAlert(
                    event.getLineId(), event.getWorkstationId(),
                    event.getAlertType(), event.getAlertLevel(),
                    event.getAlertTitle(), event.getAlertMessage());
            event.setAlertId(alertId);
        } catch (DataAccessException e) {
            log.warn("写入相机异常告警失败: type={}, title={}, error={}",
                    event.getAlertType(), event.getAlertTitle(), e.getMessage());
        }
    }
}
