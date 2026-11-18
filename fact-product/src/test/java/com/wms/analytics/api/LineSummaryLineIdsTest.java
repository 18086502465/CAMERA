package com.wms.analytics.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多产线筛选参数解析的回归测试（2026-11-17）。
 *
 * <p>背景：顶部级联支持多选产线，旧实现只取 {@code lineIds[0]} →
 * 「选 ZK09+ZK06，相机排行只出 ZK09」。新增逗号分隔 {@code lineIds} 参数，
 * 图片通道按 {@code line_id IN} 汇总。这里锁住解析器的容错语义：
 * 非法输入不 500、去重、上限 50、空值归 null（等同不筛选）。
 */
class LineSummaryLineIdsTest {

    @Test
    @DisplayName("常规逗号分隔：解析为 Long 列表")
    void parsesCommaSeparatedIds() {
        assertEquals(List.of(11L, 10L), LineAnalyticsController.parseLineIds("11,10"));
        assertEquals(List.of(3L), LineAnalyticsController.parseLineIds("3"));
    }

    @Test
    @DisplayName("容忍空格、空段与非法 token：非法值忽略而不是 500")
    void toleratesWhitespaceAndGarbage() {
        assertEquals(List.of(11L, 12L), LineAnalyticsController.parseLineIds(" 11 , , abc ,12"));
        assertNull(LineAnalyticsController.parseLineIds("abc,"));
    }

    @Test
    @DisplayName("去重并保持首次出现顺序")
    void dedupesKeepingOrder() {
        assertEquals(List.of(11L, 10L), LineAnalyticsController.parseLineIds("11,10,11,11,10"));
    }

    @Test
    @DisplayName("空 / null 归 null：等同不筛选")
    void nullishInputReturnsNull() {
        assertNull(LineAnalyticsController.parseLineIds(null));
        assertNull(LineAnalyticsController.parseLineIds(""));
        assertNull(LineAnalyticsController.parseLineIds("   "));
    }

    @Test
    @DisplayName("超长输入截断到 50 个，防御异常长串拖垮 SQL")
    void capsAtFifty() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 80; i++) {
            if (i > 1) sb.append(',');
            sb.append(i);
        }
        List<Long> parsed = LineAnalyticsController.parseLineIds(sb.toString());
        assertEquals(50, parsed.size());
        assertEquals(1L, parsed.get(0));
        assertTrue(parsed.get(49) == 50L);
    }
}
