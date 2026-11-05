package com.wms.defect.app;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 归档查询的月份区间计算（2026-11-05，图片月度归档配套）。
 *
 * <p>纯函数、无副作用，便于单测覆盖边界：默认查询（无时间范围）必须**不碰月表**；
 * 只有范围落到热区（hot-days）之外时才需要并集月表。跨界线时把区间裁到热区下界，
 * 避免与主表重复计数。</p>
 */
public final class ArchiveMonthRange {

    /** 历史查询最多回看月数（10 年，防病态范围） */
    public static final int MAX_MONTHS = 120;

    /** 历史查询的兜底下界：只给结束时间时向前回看 5 年 */
    private static final int DEFAULT_LOOKBACK_YEARS = 5;

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private ArchiveMonthRange() {
    }

    /**
     * 计算需要并集的月份（{@code yyyyMM}，升序）。
     *
     * @param startTime 查询起点（可空）
     * @param endTime   查询终点（可空）
     * @param cutoff    热区下界（now - hotDays）
     * @return 空列表 = 只查主表；否则按升序返回涉及的月份
     */
    public static List<String> months(LocalDateTime startTime, LocalDateTime endTime, LocalDateTime cutoff) {
        if (startTime == null && endTime == null) {
            return Collections.emptyList();     // 默认查询：主表就是近 hotDays 天，不碰月表
        }
        LocalDateTime effectiveEnd = endTime == null ? LocalDateTime.now() : endTime;
        LocalDateTime effectiveStart = startTime;
        if (effectiveStart == null) {
            if (!effectiveEnd.isBefore(cutoff)) {
                return Collections.emptyList();  // 只给了热区内的结束时间
            }
            effectiveStart = cutoff.minusYears(DEFAULT_LOOKBACK_YEARS);
        } else if (!effectiveStart.isBefore(cutoff)) {
            return Collections.emptyList();      // 全部落在热区
        }
        if (effectiveEnd.isAfter(cutoff)) {
            effectiveEnd = cutoff;               // 跨界线：热区部分由主表覆盖
        }
        if (effectiveEnd.isBefore(effectiveStart)) {
            return Collections.emptyList();      // 反向区间
        }
        List<String> wanted = new ArrayList<>();
        LocalDate cursor = effectiveStart.toLocalDate().withDayOfMonth(1);
        LocalDate last = effectiveEnd.toLocalDate().withDayOfMonth(1);
        while (!cursor.isAfter(last) && wanted.size() < MAX_MONTHS) {
            wanted.add(cursor.format(MONTH_FMT));
            cursor = cursor.plusMonths(1);
        }
        return wanted;
    }
}
