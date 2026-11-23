package com.wms.analytics.app.listener;

import com.wms.analytics.infra.repository.AnomalyAlertRepository;
import com.wms.shared.common.event.LicenseAlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 授权状态告警监听器（2026-11-23 决策 #7 / LIC-12）。
 *
 * <p>{@code LicenseCheckTask}（system 域）巡检到状态变化时发布 {@link LicenseAlertEvent}，
 * 本监听器负责落库 {@code fact_anomaly_alert}。此前授权到期/无效只打 WARN 日志，
 * 无人盯着 journald 就发现不了 —— 产线夜里跑着，早上才发现"昨天就过期了"。
 *
 * <p><b>去重与恢复</b>：落库走 {@link AnomalyAlertRepository#insertCameraAlert}，
 * 按 {@code (0,0,LICENSE_EXPIRED)} 去重 —— 同类型未恢复行只刷新内容不新增；
 * 授权恢复正常时按同一键找活跃行并 resolve，不产生新告警。
 *
 * <p>告警类型 {@code LICENSE_EXPIRED} 必须同步
 * {@code FactAnomalyAlertController.OPS_ALERT_TYPES}，否则会被当业务告警按级别排序、
 * 在相机告警密集时沉底（运维类告警永远最前的约定）。
 */
@Component
public class LicenseAlertListener {

    /** 告警类型 —— 必须同步 FactAnomalyAlertController.OPS_ALERT_TYPES */
    public static final String ALERT_TYPE = "LICENSE_EXPIRED";

    private static final Logger log = LoggerFactory.getLogger(LicenseAlertListener.class);

    @Autowired
    private AnomalyAlertRepository anomalyAlertRepository;

    @EventListener
    public void onLicenseAlert(LicenseAlertEvent event) {
        try {
            if (event.isRecovered()) {
                Long id = anomalyAlertRepository.findUnresolvedAlertId(0, 0, ALERT_TYPE);
                if (id != null) {
                    anomalyAlertRepository.resolveCameraAlert(id, "AUTO_RECOVER");
                    log.info("授权恢复正常，已自动关闭 {} 告警: id={}", ALERT_TYPE, id);
                }
                return;
            }

            String status = event.getStatus() == null ? "UNKNOWN" : event.getStatus();
            String title = "服务器授权异常：" + status;
            // 关键信息（状态/到期日/处置）放前 500 字 —— alert_message varchar(2000)，
            // Repository 会自动截断，但现场约定关键信息必须在前 500 字内可见。
            StringBuilder message = new StringBuilder()
                    .append("【状态】").append(status)
                    .append("【到期日】").append(event.getExpireAt() == null ? "无" : event.getExpireAt())
                    .append("【剩余天数】").append(event.getDaysLeft() == null ? "无" : event.getDaysLeft())
                    .append("【说明】").append(truncate(event.getMessage(), 300))
                    .append("。请到「服务器授权」页核对授权文件与机器码；到期后默认 warn 模式只告警不停线，")
                    .append("若合同约定 readonly/block 需在服务器配置 expired-mode，续期请联系供应商重新签发授权。");

            anomalyAlertRepository.insertCameraAlert(0, 0, ALERT_TYPE, "HIGH", title, message.toString());
        } catch (Exception e) {
            // 告警本身失败不能掩盖原始巡检日志（已在 LicenseCheckTask 落盘）
            log.warn("写入 {} 告警失败: {}", ALERT_TYPE, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
