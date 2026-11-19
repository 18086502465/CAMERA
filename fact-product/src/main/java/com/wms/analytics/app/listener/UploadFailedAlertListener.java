package com.wms.analytics.app.listener;

import com.wms.analytics.infra.repository.AnomalyAlertRepository;
import com.wms.shared.common.event.UploadFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备上传失败告警监听器（2026-11-18 评审 M-04；2026-11-19 评审 N-02 增强）。
 *
 * <p>shared 域的 {@code GlobalExceptionHandler} 捕获到 multipart 解析失败 / 客户端断流后
 * 发布 {@link UploadFailedEvent}，本监听器负责落库 {@code fact_anomaly_alert}。
 *
 * <p><b>为什么必须落告警</b>：上传失败的那张图<b>既没入库也没告警</b> ——
 * 设备端若不重试就永久丢失，而现场只有 journald 能查，运维根本发现不了
 * "今天少了几张图"。
 *
 * <p><b>去重</b>：{@link AnomalyAlertRepository#insertCameraAlert} 落库前按
 * {@code (line_id, workstation_id, alert_type)} 去重 —— 同类型未恢复告警只刷新内容，
 * 不会每次失败都插新行。本类上传失败维度固定为 0/0（解析失败拿不到业务维度），
 * 与 {@code BACKUP_FAILED} 等运维类告警同一口径。
 *
 * <p><b>★ 失败频次（N-02，2026-11-19）</b>：上面那个去重有个副作用 ——
 * <b>失败次数被吞掉了</b>。现场实测近 24h 有 <b>201 次</b>上传失败，
 * 但告警表里只有 <b>1 条</b> {@code UPLOAD_FAILED}：运维看到"有 1 条上传失败告警"，
 * 根本分不清是偶发 1 次，还是某台相机常态性失败 177 次
 * （实测 {@code DA9299030} 一台就占 177 次）。
 * <p>本类用<b>滑动窗口计数</b>补上频次：告警正文里带
 * 「最近 1 小时该设备失败 N 次（累计 M 次）」，运维一眼能判断严重程度。
 * <p>计数是<b>进程内</b>状态（重启失忆）—— 与 {@code CameraAlertThrottle} 同一量级，
 * 目的是让告警正文有"频次"这个维度，不是精确计费。
 */
@Component
public class UploadFailedAlertListener {

    /** 告警类型 —— 必须同步 {@code FactAnomalyAlertController.OPS_ALERT_TYPES}，否则会被当业务告警沉底 */
    public static final String ALERT_TYPE = "UPLOAD_FAILED";

    private static final Logger log = LoggerFactory.getLogger(UploadFailedAlertListener.class);

    /** 同一设备连续失败达到该次数才告警（过滤偶发网络抖动） */
    private static final int THRESHOLD = 1;

    /** 滑动窗口长度（毫秒）—— 告警正文里的"最近 N 小时"口径 */
    private static final long WINDOW_MS = 60L * 60L * 1000L;

    @Autowired
    private AnomalyAlertRepository anomalyAlertRepository;

    /**
     * 失败次数滑动窗口：deviceId → 失败时刻队列。
     *
     * <p>用 {@link ConcurrentHashMap} + {@link ArrayDeque}（队列头出队丢旧记录）。
     * 设备数量级是几十台，每台每次失败只做常数级操作，不会成为瓶颈。
     */
    private final Map<String, Deque<Long>> failureWindows = new ConcurrentHashMap<>();

    /** 各设备的累计失败次数（进程内，服务重启归零 —— 只作趋势参考，不作计费依据） */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> totalCounters = new ConcurrentHashMap<>();

    @EventListener
    public void onUploadFailed(UploadFailedEvent event) {
        try {
            String device = (event.getDeviceId() == null || event.getDeviceId().trim().isEmpty())
                    ? "未知设备" : event.getDeviceId().trim();
            String reason = truncate(event.getReason(), 400);
            WindowStats stats = recordFailure(device);

            String title = "设备图片上传失败：" + device;
            StringBuilder message = new StringBuilder()
                    .append("设备 ").append(device).append(" 的图片上传未能完成（")
                    .append(event.getErrorType()).append("）：").append(reason)
                    .append("。该批次图片未入库，设备端应重传失败项；若持续出现，请检查设备网络与上传队列。");
            // ★ N-02：带失败频次 —— 让运维区分「偶发 1 次」与「常态失败上百次」
            message.append("【失败频次】最近 1 小时该设备失败 ").append(stats.recentCount)
                    .append(" 次，自服务启动以来累计 ").append(stats.totalCount).append(" 次");
            if (stats.recentCount >= 10) {
                message.append("（频次较高，疑似设备端持续重传或网络不稳）");
            }

            anomalyAlertRepository.insertCameraAlert(0, 0, ALERT_TYPE, "HIGH", title, message.toString());
        } catch (Exception e) {
            // 告警本身失败不能掩盖原始异常（原始异常已在 GlobalExceptionHandler 的 log.error 落盘）
            log.warn("写入 UPLOAD_FAILED 告警失败: {}", e.getMessage());
        }
    }

    /**
     * 记录一次失败并返回窗口统计。
     *
     * <p>窗口内的失败时刻会被裁掉（超出 1 小时的记录），同时累计总数只增不减。
     */
    private WindowStats recordFailure(String device) {
        long now = System.currentTimeMillis();
        Deque<Long> queue = failureWindows.computeIfAbsent(device, k -> new ArrayDeque<>());
        int recent;
        int total;
        synchronized (queue) {
            queue.addLast(now);
            // 裁掉窗口外的旧记录
            while (!queue.isEmpty() && now - queue.peekFirst() > WINDOW_MS) {
                queue.pollFirst();
            }
            recent = queue.size();
            total = totalCounters.computeIfAbsent(device, k -> new java.util.concurrent.atomic.AtomicInteger())
                    .incrementAndGet();
        }
        return new WindowStats(recent, total);
    }

    /** 窗口统计结果 */
    private static class WindowStats {
        /** 最近 1 小时失败次数 */
        final int recentCount;
        /** 自服务启动以来累计失败次数 */
        final int totalCount;

        WindowStats(int recentCount, int totalCount) {
            this.recentCount = recentCount;
            this.totalCount = totalCount;
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
