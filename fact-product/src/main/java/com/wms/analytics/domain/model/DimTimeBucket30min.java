package com.wms.analytics.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 30分钟时间分桶维度表
 * </p>
 *
 * @author 45344
 * @since 2026-01-10
 */
@Getter
@Setter
@TableName("dim_time_bucket_30min")
public class DimTimeBucket30min implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 时间桶ID
     */
    @TableId(value = "bucket_id", type = IdType.AUTO)
    private Integer bucketId;

    /**
     * 日期部分
     */
    @TableField("full_date")
    private LocalDate fullDate;

    /**
     * 小时（0-23）
     */
    @TableField("hour")
    private Byte hour;

    /**
     * 分钟分组：00表示[xx:00~xx:30)，30表示[xx:30~yy:00)
     */
    @TableField("minute_group")
    private String minuteGroup;

    /**
     * 该30分钟段起始时间
     */
    @TableField("start_time")
    private LocalDateTime startTime;

    /**
     * 该30分钟段结束时间
     */
    @TableField("end_time")
    private LocalDateTime endTime;
}
