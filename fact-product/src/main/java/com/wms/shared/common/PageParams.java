package com.wms.shared.common;

/**
 * 分页参数归一化 —— 防止"误传超大 size"打爆数据库与内存。
 *
 * <p><b>为什么需要</b>（2026-11-18 评审 M-02）：
 * <ul>
 *   <li>MyBatis-Plus 的 {@code IPage} 分页**必带总数查询** —— 传 size=100000
 *       就是一次全表 {@code COUNT(*)} + 一次全表扫描 + 完整对象构建；</li>
 *   <li>本项目多个端点把 {@code size} 直接透传给分页对象，**没有任何上限校验**；</li>
 *   <li>前端有 6 处按"全量"语义硬编码 {@code size: 999}（首页 / 图像记录 /
 *       相机详情 / Dashboard / 配置页 ×2）—— 相机规模上去后首屏传输量线性膨胀。</li>
 * </ul>
 *
 * <p><b>口径</b>：
 * <ul>
 *   <li>{@code page < 1} → 归一为 1；</li>
 *   <li>{@code size < 1} → 归一为默认 20；</li>
 *   <li>{@code size > 上限} → 截断到上限（{@link #MAX_SIZE}），**不报错** ——
 *       截断比报错更友好，且能挡住"误传 10 万"这类爆炸输入；</li>
 *   <li>{@code size} 为 null → 用调用方给的默认值。</li>
 * </ul>
 *
 * <p><b>为什么不直接返回 400</b>：前端"全量拉取"的写法（size=999）在相机规模
 * 达到上限前是合理的；直接报错会让现有页面在规模变大时突然全挂。
 * 截断 + 响应里回显真实 size，调用方可自查。
 *
 * <p>上限取 500 的依据：现场 36 台、设计目标 200 台相机；500 足以覆盖"全量列表"
 * 场景，同时把最坏情况的扫描量钉在可接受范围内。
 */
public final class PageParams {

    /** 分页 size 上限（超过即截断） */
    public static final int MAX_SIZE = 500;

    /** 分页 size 默认值 */
    public static final int DEFAULT_SIZE = 20;

    private PageParams() {
    }

    /**
     * 归一化 page。
     *
     * @param page 原始页码（1 起）
     * @return 归一后的页码，最小为 1
     */
    public static int normalizePage(Integer page) {
        if (page == null || page < 1) {
            return 1;
        }
        return page;
    }

    /**
     * 归一化 size（截断到 {@link #MAX_SIZE}）。
     *
     * @param size    原始每页条数
     * @param defSize 调用方声明的默认值（null 时用 {@link #DEFAULT_SIZE}）
     * @return 归一后的每页条数，范围 [1, MAX_SIZE]
     */
    public static int normalizeSize(Integer size, Integer defSize) {
        int fallback = (defSize == null || defSize < 1) ? DEFAULT_SIZE : defSize;
        if (size == null) {
            return Math.min(fallback, MAX_SIZE);
        }
        if (size < 1) {
            return fallback;
        }
        return Math.min(size, MAX_SIZE);
    }

    /**
     * 归一化 size（使用默认 20）。
     */
    public static int normalizeSize(Integer size) {
        return normalizeSize(size, DEFAULT_SIZE);
    }

    /**
     * size 是否被截断过（供响应回显，让调用方知道拿到的不是全部）。
     */
    public static boolean truncated(Integer size, Integer defSize) {
        return size != null && size > MAX_SIZE;
    }
}
