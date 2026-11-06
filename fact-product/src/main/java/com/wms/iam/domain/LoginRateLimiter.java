package com.wms.iam.domain;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录/注册失败限速器（进程内，无外部依赖）。
 *
 * <p>SEC-03（2026-11-06 评审）：登录无任何频率控制 —— 口令爆破除拖垮口令校验外
 * 还能打成 BCrypt CPU DoS。本类按 key（如 {@code login|username|ip}）做滑动计数：
 *
 * <ul>
 *   <li>窗口内失败达到 {@link #MAX_FAILURES} 次 → 锁定 {@link #LOCK_MS}；</li>
 *   <li>成功一次即清零（正常用户偶尔输错不受影响）；</li>
 *   <li>纯内存实现，重启即清零 —— 对爆破的抑制足够，精确风控留待引入 Redis。</li>
 * </ul>
 *
 * <p>线程安全：ConcurrentHashMap + synchronized(entry)。容量保护：超过
 * {@link #MAX_ENTRIES} 时顺带清理过期项，防止伪造 key 撑爆内存。
 */
@Component
public class LoginRateLimiter {

    /** 窗口内允许的最大连续失败次数 */
    static final int MAX_FAILURES = 5;
    /** 失败计数窗口 */
    private static final long WINDOW_MS = 60_000L;
    /** 触发限速后的锁定时长 */
    private static final long LOCK_MS = 5 * 60_000L;
    /** 容量保护阈值，超过即清理过期 key */
    private static final int MAX_ENTRIES = 10_000;

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    private static final class Entry {
        volatile int failures;
        volatile long windowStart;
        volatile long blockedUntil;
    }

    /**
     * 该 key 当前是否被锁定。
     *
     * @return true 表示已锁定（调用方应直接拒绝并提示剩余时间）
     */
    public boolean isBlocked(String key) {
        Entry e = store.get(key);
        if (e == null) {
            return false;
        }
        if (e.blockedUntil > 0 && System.currentTimeMillis() < e.blockedUntil) {
            return true;
        }
        // 锁定已过期：顺手重置，避免留下永久陈旧条目
        if (e.blockedUntil > 0) {
            synchronized (e) {
                e.failures = 0;
                e.windowStart = 0;
                e.blockedUntil = 0;
            }
        }
        return false;
    }

    /** 锁定剩余毫秒数（未锁定返回 0），用于给用户提示。 */
    public long remainingLockMs(String key) {
        Entry e = store.get(key);
        if (e == null) {
            return 0;
        }
        long left = e.blockedUntil - System.currentTimeMillis();
        return left > 0 ? left : 0;
    }

    /** 记录一次失败；窗口内累计达到阈值即进入锁定。 */
    public void recordFailure(String key) {
        long now = System.currentTimeMillis();
        Entry e = store.computeIfAbsent(key, k -> new Entry());
        synchronized (e) {
            if (e.windowStart == 0 || now - e.windowStart > WINDOW_MS) {
                e.windowStart = now;
                e.failures = 1;
            } else {
                e.failures++;
            }
            if (e.failures >= MAX_FAILURES) {
                e.blockedUntil = now + LOCK_MS;
            }
        }
        evictIfNeeded();
    }

    /** 记录一次成功（清零计数）。 */
    public void recordSuccess(String key) {
        store.remove(key);
    }

    private void evictIfNeeded() {
        if (store.size() < MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            boolean expired = e.blockedUntil > 0 && now >= e.blockedUntil
                    || e.blockedUntil == 0 && now - e.windowStart > WINDOW_MS;
            if (expired) {
                it.remove();
            }
        }
    }
}
