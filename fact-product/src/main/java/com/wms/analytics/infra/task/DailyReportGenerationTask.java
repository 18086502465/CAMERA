package com.wms.analytics.infra.task;

import com.wms.analytics.infra.repository.AnomalyAlertRepository;
import com.wms.analytics.infra.repository.DailyReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

/**
 * 产线统计日报生成任务（2026-11-17 新增）。
 *
 * <p>每天 00:05 把<b>前一天</b>各产线的日报结论物化进 {@code fact_daily_line_report}
 * （幂等覆盖，重跑安全）。失败落 {@code DAILY_REPORT_FAILED} 运维告警，成功后自动恢复
 * —— 与备份/清理/归档告警同一套口径（脚本告警必须「同行更新 + 成功后自动恢复」）。
 *
 * <p>为什么放在 00:05：与 02:00 DataCleanupTask、02:30 图片归档、01:00 数据库全量备份
 * 错峰，且跨零点后「昨天」的数据已完整（相机端没有补传昨天的机制）。
 *
 * <p>失败可见性双重保险：① 告警表（本任务自己写，不依赖 shell）；② 任务方法返回值供
 * 手工补算端点回显。手动补算走 {@code POST /api/v1/analytics/daily-reports/{date}/regenerate}。
 */
@Component
public class DailyReportGenerationTask {

    private static final Logger log = LoggerFactory.getLogger(DailyReportGenerationTask.class);

    public static final String ALERT_TYPE = "DAILY_REPORT_FAILED";

    private final DailyReportRepository dailyReportRepository;
    private final AnomalyAlertRepository anomalyAlertRepository;
    private final TransactionTemplate transactionTemplate;

    public DailyReportGenerationTask(DailyReportRepository dailyReportRepository,
                                     AnomalyAlertRepository anomalyAlertRepository,
                                     PlatformTransactionManager transactionManager) {
        this.dailyReportRepository = dailyReportRepository;
        this.anomalyAlertRepository = anomalyAlertRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 每日 00:05 物化前一天（错开 01:00 备份 / 02:00 清理 / 02:30 归档） */
    @Scheduled(cron = "${app.daily-report.cron:0 5 0 * * ?}")
    public void scheduledGenerate() {
        boolean ok = generateFor(LocalDate.now().minusDays(1), true);
        if (ok) {
            log.info("[产线日报] 昨日快照生成完成");
        }
    }

    /**
     * 生成（或重算）某天的日报快照。
     *
     * <p>心跳累计计数（total_count 等）只有在<b>日界时刻</b>拍下来才有差分意义：
     * 定时任务在 00:05 跑「昨天」，当前计数 ≈ 昨日日终计数（分钟级漂移可接受）；
     * 而白天手工补算历史日期时，当前计数已包含之后的所有上传，拍下来必然算出错误差分
     * —— 所以补算历史日必须 {@code stampCounters=false}（累计列留 NULL，
     * 页面按「计数未接入」诚实降级，绝不给错值）。
     *
     * @param stampCounters 是否拍心跳累计计数快照（见上，仅日终任务/当天可 true）
     * @return 是否成功；失败已落 {@code DAILY_REPORT_FAILED} 告警
     */
    public boolean generateFor(LocalDate date, boolean stampCounters) {
        try {
            Integer rows = transactionTemplate.execute(status -> {
                dailyReportRepository.deleteDate(date);
                dailyReportRepository.insertRows(
                        dailyReportRepository.computeDay(date, stampCounters), date);
                return null;
            });
            resolveFailureAlert();
            log.info("[产线日报] {} 快照已写入（{} 行）", date, rows == null ? "-" : rows);
            return true;
        } catch (Exception e) {
            log.error("[产线日报] {} 快照生成失败: {}", date, e.getMessage(), e);
            raiseFailureAlert(date, e);
            return false;
        }
    }

    /** 成功后自动恢复历史失败告警（同 (0,0,type) 行内更新，符合脚本告警恢复纪律） */
    private void resolveFailureAlert() {
        try {
            Long alertId = anomalyAlertRepository.findUnresolvedAlertId(0, 0, ALERT_TYPE);
            if (alertId != null) {
                anomalyAlertRepository.resolveCameraAlert(alertId, "AUTO_RECOVER");
            }
        } catch (Exception e) {
            // 恢复失败不影响主流程（下次成功会再试）
            log.warn("[产线日报] 恢复 DAILY_REPORT_FAILED 告警失败（不影响快照）: {}", e.getMessage());
        }
    }

    private void raiseFailureAlert(LocalDate date, Exception cause) {
        try {
            String msg = "产线日报 " + date + " 快照生成失败：" + cause.getMessage()
                    + "。可用「日报页 → 补算」或等下次调度重试；重跑幂等，不会产生重复行。";
            anomalyAlertRepository.insertCameraAlert(0, 0, ALERT_TYPE, "HIGH",
                    "产线日报生成失败：" + date, msg);
        } catch (Exception e) {
            // 告警本身失败不能掩盖原始异常，原始异常已在 log.error 落盘
            log.error("[产线日报] 写入 DAILY_REPORT_FAILED 告警失败: {}", e.getMessage());
        }
    }
}
