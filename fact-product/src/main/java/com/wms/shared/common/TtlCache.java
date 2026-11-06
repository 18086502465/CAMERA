package com.wms.shared.common;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 极简 TTL 缓存（PERF-03 / v3 H-2，2026-11-06）。
 *
 * <p><b>为什么不用 Caffeine / Spring Cache</b>：
 * <ul>
 *   <li>现场是离线部署，多一个三方 jar 就多一条发版依赖链；本类逻辑不到 100 行；</li>
 *   <li>这里只需要「键 + 固定 TTL + 容量上限」，不需要淘汰算法、统计、异步加载；</li>
 *   <li>语义可控：过期即失效、容量满时先清过期再随机淘汰，行为对使用者完全透明。</li>
 * </ul>
 *
 * <p><b>并发与可变性约定</b>：
 * <ul>
 *   <li>线程安全（{@link ConcurrentHashMap} + 每次 get 校验时间戳）；</li>
 *   <li><b>缓存值是共享对象</b>：取用方必须只读，不得原地修改（本项目用于缓存响应 Map，
 *       只经 Jackson 序列化，满足此约束）；</li>
 *   <li>允许缓存 {@code null}？不允许 —— {@link #get} 返回 null 即表示未命中。</li>
 * </ul>
 *
 * <p><b>本类自身不做任何业务判断</b>：命中率、键设计、失效策略都由调用方决定。
 */
public class TtlCache<K, V> {

    private final long ttlMillis;
    private final int maxSize;
    private final ConcurrentHashMap<K, Entry<V>> store = new ConcurrentHashMap<>();
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    public TtlCache(long ttlMillis, int maxSize) {
        if (ttlMillis <= 0) throw new IllegalArgumentException("ttlMillis 必须大于 0");
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize 必须大于 0");
        this.ttlMillis = ttlMillis;
        this.maxSize = maxSize;
    }

    /** 读取；过期或不存在返回 null（过期项顺手删除） */
    public V get(K key) {
        if (key == null) return null;
        Entry<V> entry = store.get(key);
        if (entry == null) {
            missCount.incrementAndGet();
            return null;
        }
        if (entry.expireAt <= System.currentTimeMillis()) {
            store.remove(key, entry);
            missCount.incrementAndGet();
            return null;
        }
        hitCount.incrementAndGet();
        return entry.value;
    }

    /** 写入（容量超限时先清过期项，仍超限则放弃写入 —— 宁可少缓存，不可无界增长） */
    public void put(K key, V value) {
        if (key == null || value == null) return;
        if (store.size() >= maxSize) {
            purgeExpired();
            if (store.size() >= maxSize) {
                return;
            }
        }
        store.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMillis));
    }

    /** 移除单个键 */
    public void invalidate(K key) {
        if (key != null) store.remove(key);
    }

    /** 清空 */
    public void clear() {
        store.clear();
    }

    public int size() {
        return store.size();
    }

    public long hitCount() {
        return hitCount.get();
    }

    public long missCount() {
        return missCount.get();
    }

    /** 清理已过期项，返回清理条数 */
    public int purgeExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<K, Entry<V>>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expireAt <= now) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    private static final class Entry<V> {
        final V value;
        final long expireAt;

        Entry(V value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }
}
