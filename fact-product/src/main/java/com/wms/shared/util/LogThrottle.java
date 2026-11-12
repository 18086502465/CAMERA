package com.wms.shared.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志节流：同一 key 在 intervalMs 内只放行一次。
 *
 * <p><b>为什么需要</b>：心跳是 5 秒/台的高频链路，写在它上面的「未配置 / 降级」
 * 类告警不节流就会淹没真实告警。实测（2026-11-12 上线后）：16 台相机一分钟
 * 打出 34 条「相机未配置设备令牌」，占该时段日志行的 45%，日增约 4.9 万条。
 *
 * <p>纯静态工具、无 Spring 依赖，供 {@code api} / {@code app} 层直接调用。
 * key 建议带业务前缀（如 {@code "token-not-configured:DA9200611"}），避免不同
 * 场景互相压制。
 */
public final class LogThrottle {

    private static final Map<String, Long> LAST_AT = new ConcurrentHashMap<>();

    /**
     * key 数量上限。正常情况下 key 数 ≈ 相机数，远小于此值；
     * 一旦超过说明 key 里混进了高基数字段（如每台设备的 session id），
     * 直接清空自愈，避免无界增长。
     */
    private static final int MAX_KEYS = 2000;

    private LogThrottle() {
    }

    /**
     * @return true 表示本次允许记录（首次或已过间隔），false 表示应静默
     */
    public static boolean allow(String key, long intervalMs) {
        if (key == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_AT.get(key);
        if (last != null && now - last < intervalMs) {
            return false;
        }
        if (LAST_AT.size() > MAX_KEYS) {
            LAST_AT.clear();
        }
        LAST_AT.put(key, now);
        return true;
    }
}
