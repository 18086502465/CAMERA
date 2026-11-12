package com.wms.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志节流回归。
 *
 * <p>背景：心跳上线「未配置设备令牌」告警后，实测 16 台相机一分钟 34 条、
 * 占该时段日志 45%。节流失效 = 真实告警被淹没，所以这里把行为钉死。
 */
class LogThrottleTest {

    @Test
    @DisplayName("首次放行，间隔内静默")
    void firstAllowedThenSuppressed() {
        String key = "test-basic-" + System.nanoTime();
        assertTrue(LogThrottle.allow(key, 60_000L));
        assertFalse(LogThrottle.allow(key, 60_000L));
        assertFalse(LogThrottle.allow(key, 60_000L));
    }

    @Test
    @DisplayName("不同 key 互不影响")
    void independentKeys() {
        String a = "test-a-" + System.nanoTime();
        String b = "test-b-" + System.nanoTime();
        assertTrue(LogThrottle.allow(a, 60_000L));
        assertTrue(LogThrottle.allow(b, 60_000L));
        assertFalse(LogThrottle.allow(a, 60_000L));
    }

    @Test
    @DisplayName("间隔为 0 时每次都放行（用于必须逐条记录的场合）")
    void zeroIntervalAlwaysAllows() {
        String key = "test-zero-" + System.nanoTime();
        assertTrue(LogThrottle.allow(key, 0L));
        assertTrue(LogThrottle.allow(key, 0L));
    }

    @Test
    @DisplayName("null key 不抑制，避免调用方漏传参数导致静默丢日志")
    void nullKeyAlwaysAllowed() {
        assertTrue(LogThrottle.allow(null, 60_000L));
        assertTrue(LogThrottle.allow(null, 60_000L));
    }
}
