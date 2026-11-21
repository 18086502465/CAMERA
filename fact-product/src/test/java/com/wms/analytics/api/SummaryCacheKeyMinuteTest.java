package com.wms.analytics.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * line-summary 缓存键精度契约（2026-11-21 性能 P0-1）。
 *
 * <p>背景：前端默认时间范围的 end 取「当前秒」，后端缓存键原本也精确到秒
 * → 每次刷新页面都是新 key，60s TTL 从未命中，实测近 7 天每次重算 3.65s。
 *
 * <p>冻结点：同一分钟内不同秒必须共用 key；跨分钟必须换 key。
 * SQL 仍用原始秒级时间，本测试只锁缓存键，不碰统计口径。
 */
class SummaryCacheKeyMinuteTest {

    @Test
    @DisplayName("缓存键：end 归一化到分钟，同一分钟内不同秒共用 key")
    void cacheKeyTruncatesEndToMinute() throws Exception {
        LineAnalyticsController controller = new LineAnalyticsController();
        Method method = LineAnalyticsController.class.getDeclaredMethod("summaryCacheKey",
                LocalDateTime.class, LocalDateTime.class, Long.class, Long.class,
                List.class, Long.class, List.class,
                Long.class, Long.class, Long.class, Long.class,
                String.class, String.class, String.class);
        method.setAccessible(true);

        Object[] args = {
                LocalDateTime.of(2026, 11, 21, 0, 0), null, null, null,
                Collections.emptyList(), null, Collections.emptyList(),
                null, null, null, null, null, null, null
        };
        args[1] = LocalDateTime.of(2026, 11, 21, 12, 45, 37);
        String first = (String) method.invoke(controller, args);
        args[1] = LocalDateTime.of(2026, 11, 21, 12, 45, 58);
        String sameMinute = (String) method.invoke(controller, args);
        args[1] = LocalDateTime.of(2026, 11, 21, 12, 46, 1);
        String nextMinute = (String) method.invoke(controller, args);

        assertEquals(first, sameMinute, "同一分钟不同秒必须命中同一缓存键");
        assertNotEquals(first, nextMinute, "跨分钟必须换键，避免跨分钟数据混用");
    }
}
