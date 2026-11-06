package com.wms.device.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 心跳告警去重节流契约测试（v3 H-4）。
 *
 * <p>这组用例守护的是「一台相机持续故障不能把告警表刷爆」这条底线：
 * <ul>
 *   <li>首次成立必须报（不能因为去重把真故障吞掉）；</li>
 *   <li>窗口内重复必须抑制（1.7 万条/天 → 48 条/天）；</li>
 *   <li>窗口到期仍持续必须续报（否则三天前的告警没人再看见）；</li>
 *   <li>恢复必须有确认期（抖动不能反复开关）；</li>
 *   <li>恢复后必须能定位到具体告警行（否则"已恢复"无处可写）。</li>
 * </ul>
 */
class CameraAlertThrottleTest {

    private static final String KEY = "DA8119489|CAMERA_FAULT";
    private static final long T0 = 1_700_000_000_000L;

    @Test
    @DisplayName("首次成立 → RAISE")
    void firstActiveRaises() {
        CameraAlertThrottle throttle = new CameraAlertThrottle();
        CameraAlertThrottle.Outcome outcome = throttle.evaluate(KEY, true, T0);

        assertTrue(outcome.shouldRaise());
        assertNull(outcome.getSupersedeAlertId(), "首次没有可接续的上一条");
        assertEquals(0, outcome.getSuppressedCount());
    }

    @Test
    @DisplayName("窗口内重复 → SUPPRESS，且抑制计数累加、不写库")
    void repeatedWithinWindowSuppressed() {
        CameraAlertThrottle throttle = new CameraAlertThrottle();
        throttle.evaluate(KEY, true, T0);

        // 模拟 5 秒心跳连续 10 次（50 秒），全部落在 30 分钟窗口内
        for (int i = 1; i <= 10; i++) {
            CameraAlertThrottle.Outcome outcome = throttle.evaluate(KEY, true, T0 + i * 5_000L);
            assertTrue(outcome.isSuppressed(), "第 " + i + " 次心跳应被抑制");
        }
        CameraAlertThrottle.Outcome last = throttle.evaluate(KEY, true, T0 + 55_000L);
        assertEquals(11, last.getSuppressedCount(), "抑制计数应为 11");
    }

    @Test
    @DisplayName("窗口到期仍持续 → 续报 RAISE，并给出上一条告警 ID")
    void reRaiseAfterWindow() {
        CameraAlertThrottle throttle = new CameraAlertThrottle();
        throttle.evaluate(KEY, true, T0);
        throttle.bindAlertId(KEY, 9001L);
        // 窗口内先抑制若干次
        for (int i = 0; i < 5; i++) {
            throttle.evaluate(KEY, true, T0 + i * 60_000L);
        }

        long afterWindow = T0 + CameraAlertThrottle.DEDUP_WINDOW_MS + 1;
        CameraAlertThrottle.Outcome outcome = throttle.evaluate(KEY, true, afterWindow);

        assertTrue(outcome.shouldRaise(), "超过 30 分钟仍故障必须续报");
        assertEquals(9001L, outcome.getSupersedeAlertId(), "续报需带上一条 ID 以便置 SUPERSEDED");
        assertTrue(outcome.getSuppressedCount() > 0, "消息里要能体现期间抑制了多少条");
        assertEquals(T0, outcome.getActiveSinceMillis(), "故障起始时间应保持为首条时间");
    }

    @Test
    @DisplayName("条件消失未满确认期 → 不判定恢复（抗抖动）")
    void recoveryWaitsForGrace() {
        CameraAlertThrottle throttle = new CameraAlertThrottle();
        throttle.evaluate(KEY, true, T0);
        throttle.bindAlertId(KEY, 9002L);

        // 条件刚消失 30 秒：可能是抖动，不能立刻关闭告警
        CameraAlertThrottle.Outcome early = throttle.evaluate(KEY, false, T0 + 30_000L);
        assertEquals(CameraAlertThrottle.Action.NONE, early.getAction());
        assertNull(early.getResolvedAlertId());

        // 但此刻条件又成立了 → 会话继续，不应被判成新故障
        CameraAlertThrottle.Outcome back = throttle.evaluate(KEY, true, T0 + 35_000L);
        assertTrue(back.isSuppressed(), "抖动回来仍算同一次故障，不能新增告警");
    }

    @Test
    @DisplayName("条件持续消失超过确认期 → RECOVER，并带回告警 ID")
    void recoveryAfterGrace() {
        CameraAlertThrottle throttle = new CameraAlertThrottle();
        throttle.evaluate(KEY, true, T0);
        throttle.bindAlertId(KEY, 9003L);

        long recoveredAt = T0 + CameraAlertThrottle.RECOVERY_GRACE_MS + 1;
        CameraAlertThrottle.Outcome outcome = throttle.evaluate(KEY, false, recoveredAt);

        assertTrue(outcome.shouldRecover());
        assertEquals(9003L, outcome.getResolvedAlertId(), "恢复必须能定位到具体告警行");
        assertFalse(throttle.evaluate(KEY, false, recoveredAt + 5_000L).shouldRecover(),
                "同一次恢复不能被重复上报");
    }

    @Test
    @DisplayName("恢复后再次故障 → 视为新事件，重新 RAISE（不复用旧 ID）")
    void newEpisodeAfterRecovery() {
        CameraAlertThrottle throttle = new CameraAlertThrottle();
        throttle.evaluate(KEY, true, T0);
        throttle.bindAlertId(KEY, 9004L);
        long recoveredAt = T0 + CameraAlertThrottle.RECOVERY_GRACE_MS + 1;
        throttle.evaluate(KEY, false, recoveredAt);
        throttle.evaluate(KEY, false, recoveredAt + 5_000L);

        CameraAlertThrottle.Outcome again = throttle.evaluate(KEY, true, recoveredAt + 10_000L);
        assertTrue(again.shouldRaise());
        assertNull(again.getSupersedeAlertId(), "新事件没有可接续的上一条");
    }

    @Test
    @DisplayName("不同相机 / 不同类型互相独立（键按 camera_code 隔离）")
    void keysAreIsolated() {
        CameraAlertThrottle throttle = new CameraAlertThrottle();
        assertTrue(throttle.evaluate("DA8119489|CAMERA_FAULT", true, T0).shouldRaise());
        assertTrue(throttle.evaluate("DA4733319|CAMERA_FAULT", true, T0).shouldRaise(),
                "另一台相机的同类告警不能被抑制");
        assertTrue(throttle.evaluate("DA8119489|CPU_HIGH", true, T0).shouldRaise(),
                "同一台相机的另一类告警不能被抑制");
        assertTrue(throttle.evaluate("DA8119489|CAMERA_FAULT", true, T0 + 5_000L).isSuppressed());
        assertEquals(3, throttle.size());
    }

    @Test
    @DisplayName("空键（设备未上报 code）降级为「每次都报」，绝不因去重丢告警")
    void nullKeyFallsBackToAlwaysRaise() {
        CameraAlertThrottle throttle = new CameraAlertThrottle();
        assertTrue(throttle.evaluate(null, true, T0).shouldRaise());
        assertTrue(throttle.evaluate(null, true, T0 + 1_000L).shouldRaise());
        assertEquals(CameraAlertThrottle.Action.NONE, throttle.evaluate(null, false, T0 + 2_000L).getAction());
    }
}
