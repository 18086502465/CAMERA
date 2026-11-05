package com.wms.masterdata.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 班次维度表
 * </p>
 *
 * @author 45344
 * @since 2026-01-15
 */
@Getter
@Setter
@TableName("dim_shift")
public class DimShift implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 班次ID
     */
    @TableId(value = "shift_id", type = IdType.AUTO)
    private Integer shiftId;

    /**
     * 班次代码：DAY=白班，NIGHT=晚班
     */
    @TableField("shift_code")
    private String shiftCode;

    /**
     * 班次名称
     */
    @TableField("shift_name")
    private String shiftName;

    /**
     * 开始时间，如 08:00:00
     */
    @TableField("start_time")
    private LocalTime startTime;

    /**
     * 结束时间，如 20:00:00
     */
    @TableField("end_time")
    private LocalTime endTime;
}
