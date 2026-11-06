package com.wms.shared.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 短 TTL 缓存契约测试（v3 H-2）。
 *
 * <p>重点不是"能缓存"，而是三条**边界**：
 * 过期必须失效（不能把昨天的报表答案留在内存里）、容量必须有界（不能让缓存变成内存泄漏）、
 * 空值不得写入（否则"未命中"与"命中空"无法区分）。
 */
class TtlCacheTest {

    @Test
    @DisplayName("写入后可命中，命中/未命中计数正确")
    void hitAndMiss() {
        TtlCache<String, String> cache = new TtlCache<>(60_000, 16);
        assertNull(cache.get("k"));
        cache.put("k", "v");
        assertEquals("v", cache.get("k"));
        assertEquals(1, cache.hitCount());
        assertEquals(1, cache.missCount());
    }

    @Test
    @DisplayName("超过 TTL 立即失效（不用等清理）")
    void expiresAfterTtl() throws Exception {
        TtlCache<String, String> cache = new TtlCache<>(30, 16);
        cache.put("k", "v");
        assertEquals("v", cache.get("k"));
        Thread.sleep(60);
        assertNull(cache.get("k"), "过期项必须读不到");
        assertEquals(0, cache.size(), "过期项应被读取路径顺手清掉");
    }

    @Test
    @DisplayName("容量上限：满且无可清理项时放弃写入，size 不增长")
    void boundedByMaxSize() {
        TtlCache<Integer, String> cache = new TtlCache<>(60_000, 3);
        for (int i = 0; i < 10; i++) {
            cache.put(i, "v" + i);
        }
        assertEquals(3, cache.size(), "缓存必须保持有界");
    }

    @Test
    @DisplayName("容量已满但有过期项时，先清过期再写入")
    void purgeExpiredBeforeGivingUp() throws Exception {
        TtlCache<String, String> cache = new TtlCache<>(30, 2);
        cache.put("a", "1");
        cache.put("b", "2");
        Thread.sleep(60);
        cache.put("c", "3");
        assertEquals(1, cache.size());
        assertEquals("3", cache.get("c"));
    }

    @Test
    @DisplayName("null 键 / null 值不入缓存（避免把”查不到“缓存成结果）")
    void nullsAreIgnored() {
        TtlCache<String, String> cache = new TtlCache<>(60_000, 4);
        cache.put(null, "v");
        cache.put("k", null);
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("invalidate / clear 生效")
    void invalidation() {
        TtlCache<String, String> cache = new TtlCache<>(60_000, 4);
        cache.put("a", "1");
        cache.invalidate("a");
        assertNull(cache.get("a"));

        cache.put("b", "2");
        cache.clear();
        assertEquals(0, cache.size());
        assertTrue(cache.missCount() >= 1);
    }
}
