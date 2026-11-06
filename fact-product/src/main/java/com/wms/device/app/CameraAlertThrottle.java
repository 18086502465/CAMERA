package com.wms.device.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 相机告警去重节流（SEC-NEW-05 / v3 H-4，2026-11-06）。
 *
 * <p><b>问题现场</b>：{@code CameraStatusServiceImpl.checkAndAlert} 每次心跳（5 秒一次）
 * 独立做 4 类阈值判定并直接插一条 {@code fact_anomaly_alert}，没有任何去重。
 * 一台相机持续故障 → 每天 17,280 条 CRITICAL。后果不是"表变大"这么轻：
 * <ul>
 *   <li>告警列表被同一条信息刷满，真正的新告警被埋掉（<b>告警疲劳本身就是安全事故</b>）；</li>
 *   <li>按告警条数做的任何统计（趋势、Top 类型）全部失真；</li>
 *   <li>写放大直接压在心跳主链路上，故障时反而更容易把服务拖慢。</li>
 * </ul>
 *
 * <p><b>策略</b>（键 = {@code camera_code|alert_type}，camera_code 是第一身份标识）：
 * <table border="1">
 *   <tr><th>情形</th><th>动作</th></tr>
 *   <tr><td>条件首次成立</td><td>{@link Action#RAISE}：插入 1 条告警</td></tr>
 *   <tr><td>条件持续，窗口（30 分钟）内</td><td>{@link Action#SUPPRESS}：只累加抑制计数，不写库</td></tr>
 *   <tr><td>条件持续，超过窗口</td><td>{@link Action#RAISE}：续报 1 条（消息里带"已持续 X 分钟 / 期间抑制 N 条"），
 *       并把上一条标记为 {@code SUPERSEDED}，保证<b>同一时刻同一 key 只有 1 条未恢复</b></td></tr>
 *   <tr><td>条件消失且稳定 ≥2 分钟</td><td>{@link Action#RECOVER}：把当前未恢复条目标记为已恢复</td></tr>
 * </table>
 *
 * <p>为什么保留"续报"而不是"一条到底"：一条三天前的未恢复告警会被所有人忽略；
 * 每 30 分钟续报一次（折合每相机每类型 48 条/天，比 17,280 条下降 99.7%），
 * 既不刷屏又能让"还在坏"这件事持续可见。
 *
 * <p><b>为什么状态放在进程内</b>：{@code fact_anomaly_alert} 表没有 camera_code 列，
 * 无法在库侧按相机去重（用 message 文本匹配是不可接受的）。代价是服务重启后
 * 首次心跳会新开一条告警、旧的那条留在未恢复状态 —— 属于可接受的降级，
 * 由 {@code resolved_by=AUTO_RECOVER} 之外的运维手工关闭兜底。
 *
 * <p>线程安全：{@link #evaluate} 整体加锁（临界区只有几次 Map 读写，微秒级），
 * 心跳并发量（万级相机 × 5s）远达不到锁竞争的水平。
 */
@Component
public class CameraAlertThrottle {

    private static final Logger log = LoggerFactory.getLogger(CameraAlertThrottle.class);

    /** 同 key 去重窗口：窗口内最多 1 条 */
    static final long DEDUP_WINDOW_MS = 30 * 60 * 1000L;

    /** 恢复确认期：条件消失后需持续这么久才判定恢复（抗抖动） */
    static final long RECOVERY_GRACE_MS = 2 * 60 * 1000L;

    /** 状态条目上限（相机数 × 告警类型数，正常几百条；超限时清理已恢复/长期无心跳的条目） */
    static final int MAX_ENTRIES = 5000;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** 判定动作 */
    public enum Action {
        /** 需要插入新告警 */
        RAISE,
        /** 窗口内重复，抑制（不写库） */
        SUPPRESS,
        /** 已恢复，需要关闭运维侧未恢复条目 */
        RECOVER,
        /** 无需任何动作 */
        NONE
    }

    /** 判定结果（不可变） */
    public static final class Outcome {
        private final Action action;
        private final Long relatedAlertId;
        private final long suppressedCount;
        private final long activeSinceMillis;

        Outcome(Action action, Long relatedAlertId, long suppressedCount, long activeSinceMillis) {
            this.action = action;
            this.relatedAlertId = relatedAlertId;
            this.suppressedCount = suppressedCount;
            this.activeSinceMillis = activeSinceMillis;
        }

        public Action getAction() {
            return action;
        }

        /** 续报时要置为 SUPERSEDED 的上一条告警 ID，首次为 null */
        public Long getSupersedeAlertId() {
            return action == Action.RAISE ? relatedAlertId : null;
        }

        /** 恢复时要关闭的未恢复告警 ID，无关联时为 null */
        public Long getResolvedAlertId() {
            return action == Action.RECOVER ? relatedAlertId : null;
        }

        /** 上一轮窗口内被抑制的次数（用于消息里体现"其实一直在报"） */
        public long getSuppressedCount() {
            return suppressedCount;
        }

        /** 本段故障的起始时间戳（毫秒），用于计算"已持续 X 分钟" */
        public long getActiveSinceMillis() {
            return activeSinceMillis;
        }

        public boolean shouldRaise() {
            return action == Action.RAISE;
        }

        public boolean shouldRecover() {
            return action == Action.RECOVER;
        }

        public boolean isSuppressed() {
            return action == Action.SUPPRESS;
        }
    }

    /**
     * 判定当前心跳应当如何处理某类告警。
     *
     * @param key    去重键（建议 {@code camera_code|alert_type}）
     * @param active 本次心跳下该告警条件是否成立
     * @param now    当前时间戳（毫秒，允许测试注入）
     */
    public synchronized Outcome evaluate(String key, boolean active, long now) {
        if (key == null) {
            return new Outcome(active ? Action.RAISE : Action.NONE, null, 0, now);
        }
        Entry entry = entries.get(key);
        if (active) {
            if (entry == null) {
                if (entries.size() >= MAX_ENTRIES) {
                    evictStale(now);
                }
                entries.put(key, new Entry(now, now));
                return new Outcome(Action.RAISE, null, 0, now);
            }
            entry.lastActiveAt = now;
            if (now - entry.lastRaisedAt >= DEDUP_WINDOW_MS) {
                long suppressed = entry.suppressed;
                entry.lastRaisedAt = now;
                entry.suppressed = 0;
                entry.activeSince = entry.activeSince <= 0 ? now : entry.activeSince;
                return new Outcome(Action.RAISE, entry.alertId, suppressed, entry.activeSince);
            }
            entry.suppressed++;
            return new Outcome(Action.SUPPRESS, null, entry.suppressed, entry.activeSince);
        }
        // 条件不成立：需要稳定 RECOVERY_GRACE_MS 才判定恢复
        if (entry == null) {
            return new Outcome(Action.NONE, null, 0, now);
        }
        if (now - entry.lastActiveAt >= RECOVERY_GRACE_MS) {
            entries.remove(key);
            return new Outcome(Action.RECOVER, entry.alertId, entry.suppressed, entry.activeSince);
        }
        return new Outcome(Action.NONE, null, 0, entry.activeSince);
    }

    /** 告警落库后回填 ID，供下次续报/恢复时定位 */
    public synchronized void bindAlertId(String key, Long alertId) {
        if (key == null || alertId == null) return;
        Entry entry = entries.get(key);
        if (entry != null) {
            entry.alertId = alertId;
        }
    }

    /** 清空（运维/测试用） */
    public synchronized void reset() {
        entries.clear();
    }

    /** 当前跟踪的告警条目数（监控用） */
    public int size() {
        return entries.size();
    }

    /** 清理长期无心跳的条目（相机下线后再也不上报 → 条目会永久滞留） */
    private void evictStale(long now) {
        int removed = 0;
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (now - e.lastActiveAt > 24 * 60 * 60 * 1000L) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("告警节流表清理 {} 条长期未活跃条目，剩余 {}", removed, entries.size());
        }
    }

    private static final class Entry {
        long lastRaisedAt;
        long lastActiveAt;
        long activeSince;
        long suppressed;
        Long alertId;

        Entry(long lastRaisedAt, long activeSince) {
            this.lastRaisedAt = lastRaisedAt;
            this.lastActiveAt = lastRaisedAt;
            this.activeSince = activeSince;
        }
    }
}
