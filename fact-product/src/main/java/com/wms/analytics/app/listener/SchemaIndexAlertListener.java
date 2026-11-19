package com.wms.analytics.app.listener;

import com.wms.analytics.infra.repository.AnomalyAlertRepository;
import com.wms.shared.common.event.SchemaIndexFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动期建索引失败告警监听器（2026-11-19 评审 P1-1）。
 *
 * <p>{@code DefectImageRepository.ensureIndex()} 在<b>唯一索引</b>建失败时发布
 * {@link SchemaIndexFailedEvent}，本监听器把它落进 {@code fact_anomaly_alert}。
 *
 * <p><b>为什么必须告警</b>：唯一索引 {@code uk_defect_file_camera_file_name}
 * 是「上传幂等」的**最后一道防线**。它缺失时，设备端重传会**静默产生重复数据**
 * —— 同一张图重复入库、每条重复行还带一份重复的缺陷框 → **缺陷框统计被放大**。
 * 原实现失败只记一条 WARN 就放弃：不重试、无告警，运维不会注意到。
 *
 * <p><b>严重级别</b>：
 * <ul>
 *   <li>唯一索引失败 → <b>HIGH</b>（防线缺失，统计数据可能被污染）；</li>
 *   <li>普通索引失败 → 只记日志不落告警（性能问题，下次发版再建）。</li>
 * </ul>
 *
 * <p><b>自动恢复</b>：下次发版/重启时若索引能建成，应关闭该告警 ——
 * 运维补完重复数据并重建索引后重启即可（此时不再发布失败事件），
 * 因此本类<b>不主动</b> resolve；由运维确认后手工关闭，避免"修好了但告警还挂着"。
 */
@Component
public class SchemaIndexAlertListener {

    /** 告警类型 —— 必须同步 {@code FactAnomalyAlertController.OPS_ALERT_TYPES}，否则会被当业务告警沉底 */
    public static final String ALERT_TYPE = "SCHEMA_INDEX_MISSING";

    private static final Logger log = LoggerFactory.getLogger(SchemaIndexAlertListener.class);

    @Autowired
    private AnomalyAlertRepository anomalyAlertRepository;

    @EventListener
    public void onSchemaIndexFailed(SchemaIndexFailedEvent event) {
        try {
            String title = "数据库索引缺失：" + event.getTableName() + "." + event.getIndexName();
            StringBuilder message = new StringBuilder()
                    .append("启动期补建索引失败：").append(event.getTableName()).append(".")
                    .append(event.getIndexName())
                    .append("（").append(event.isUniqueIndex() ? "唯一索引" : "普通索引").append("）。原因：")
                    .append(truncate(event.getReason(), 300));
            if (event.isUniqueIndex()) {
                message.append("。⚠ 唯一索引缺失 = **上传幂等的最后一道防线失效**：")
                        .append("设备端未收到响应会重传同一张图，缺失该索引会导致重复数据入库，")
                        .append("且每条重复行会带一份重复的缺陷框 → 缺陷框统计被同步放大。")
                        .append("处置：① 用 docs/scripts/purge_duplicate_images.sql 清理重复数据")
                        .append("（默认只预览，确认后再删；脚本会建备份表保留 7 天）；")
                        .append("② 重新启动服务即可自动补建索引；③ 确认 fact_anomaly_alert 中本条已解决。");
            } else {
                message.append("。普通索引缺失只影响查询性能，下次发版/重启会自动补建。");
            }
            anomalyAlertRepository.insertCameraAlert(0, 0, ALERT_TYPE, "HIGH", title, message.toString());
        } catch (Exception e) {
            // 告警本身失败不能掩盖原始问题（原始异常已在 Repository 里记过 WARN）
            log.warn("写入 SCHEMA_INDEX_MISSING 告警失败: {}", e.getMessage());
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
