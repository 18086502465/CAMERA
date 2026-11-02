package com.wms.utils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;

/**
 * 班次计算工具类
 * 固定班次：白班 08:00-20:00，晚班 20:00-08:00（跨天）
 */
public class ShiftCalculator {

    // 白班开始/结束
    public static final LocalTime DAY_SHIFT_START = LocalTime.of(8, 0);
    public static final LocalTime DAY_SHIFT_END = LocalTime.of(20, 0);

    // 晚班开始/结束
    public static final LocalTime NIGHT_SHIFT_START = LocalTime.of(20, 0);
    public static final LocalTime NIGHT_SHIFT_END = LocalTime.of(8, 0);

    /**
     * 根据时间判断所属班次
     * @return "DAY" 或 "NIGHT"
     */
    public static String getShiftByTime(LocalDateTime dateTime) {
        LocalTime time = dateTime.toLocalTime();
        // 晚班：20:00 ~ 08:00（跨天）
        if (!time.isBefore(NIGHT_SHIFT_START) || !time.isAfter(NIGHT_SHIFT_END)) {
            return "NIGHT";
        } else {
            return "DAY";
        }
    }

    /**
     * 根据日期判断是否为工作日（需结合节假日配置表）
     * 默认：周一到周五为工作日，周六周日为休息日
     */
    public static boolean isWorkday(LocalDate date, boolean isHoliday) {
        if (isHoliday) {
            return false; // 节假日配置表标记为休息日
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /**
     * 获取指定时间的班次ID（关联 dim_shift 表）
     * shift_id: 1=白班, 2=晚班
     */
    public static Integer getShiftId(LocalDateTime dateTime) {
        String shift = getShiftByTime(dateTime);
        return "DAY".equals(shift) ? 1 : 2;
    }

    /**
     * 将 Date 转为 LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(Date date) {
        if (date == null) return null;
        return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }

    /**
     * 将 LocalDateTime 转为 Date
     */
    public static Date toDate(LocalDateTime ldt) {
        if (ldt == null) return null;
        return Date.from(ldt.atZone(java.time.ZoneId.systemDefault()).toInstant());
    }
}
