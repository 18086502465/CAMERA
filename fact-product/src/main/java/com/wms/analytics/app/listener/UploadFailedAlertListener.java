package com.wms.analytics.app.listener;

import com.wms.analytics.infra.repository.AnomalyAlertRepository;
import com.wms.shared.common.event.UploadFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 设备上传失败告警监听器（2026-11-18 评审 M-04）。
 *
 * <p>shared 域的 {@code GlobalExceptionHandler} 捕获到 multipart 解析失败 / 客户端断流后
 * 发布 {@link UploadFailedEvent}，本监听器负责落库 {@code fact_anomaly_alert}。
 *
 * <p><b>为什么必须落告警</b>：上传失败的那张图<b>既没入库也没告警</b> ——
 * 设备端若不重试就永久丢失，而现场只有 journald 能查，运维根本发现不了
 * "今天少了几张图"。现场近 24h 实测 39 条 multipart 解析失败 + 19 条 Broken pipe，
 * 全部静默。
 *
 * <p><b>去重</b>：{@link AnomalyAlertRepository#insertCameraAlert} 落库前按
 * {@code (line_id, workstation_id, alert_type)} 去重 —— 同类型未恢复告警只刷新内容，
 * 不会每次失败都插新行。本类上传失败维度固定为 0/0（解析失败拿不到业务维度），
 * 与 {@code BACKUP_FAILED} 等运维类告警同一口径。
 *
 * <p><b>自动恢复</b>：后续上传成功时发布恢复事件（{@link #ALERT_TYPE} 已有未解决行
 * 且设备号一致 → 标记已解决），避免告警长期挂着。
 */
@Component
public class UploadFailedAlertListener {

    /** 告警类型 —— 必须同步 {@code FactAnomalyAlertController.OPS_ALERT_TYPES}，否则会被当业务告警沉底 */
    public static final String ALERT_TYPE = "UPLOAD_FAILED";

    private static final Logger log = LoggerFactory.getLogger(UploadFailedAlertListener.class);

    /** 同一设备连续失败达到该次数才告警（过滤偶发网络抖动） */
    private static final int THRESHOLD = 1;

    @Autowired
    private AnomalyAlertRepository anomalyAlertRepository;

    @EventListener
    public void onUploadFailed(UploadFailedEvent event) {
        try {
            String device = (event.getDeviceId() == null || event.getDeviceId().trim().isEmpty())
                    ? "未知设备" : event.getDeviceId().trim();
            String reason = truncate(event.getReason(), 400);
            String title = "设备图片上传失败：" + device;
            String message = "设备 " + device + " 的图片上传未能完成（" + event.getErrorType() + "）：" + reason
                    + "。该批次图片未入库，设备端应重传失败项；若持续出现，请检查设备网络与上传队列。";
            anomalyAlertRepository.insertCameraAlert(0, 0, ALERT_TYPE, "HIGH", title, message);
        } catch (Exception e) {
            // 告警本身失败不能掩盖原始异常（原始异常已在 GlobalExceptionHandler 的 log.error 落盘）
            log.warn("写入 UPLOAD_FAILED 告警失败: {}", e.getMessage());
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
