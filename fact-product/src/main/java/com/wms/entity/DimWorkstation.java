package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 工位维度表
 * </p>
 *
 * @author 45344
 * @since 2026-01-15
 */
@Getter
@Setter
@TableName("dim_workstation")
public class DimWorkstation implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 工位ID
     */
    @TableId(value = "workstation_id", type = IdType.AUTO)
    private Integer stationId;

    /**
     * 工位编码，如 WS01
     */
    @TableField("station_code")
    private String stationCode;

    /**
     * 工位名称
     */
    @TableField("station_name")
    private String stationName;

    /**
     * 所属产线ID
     */
    @TableField("line_id")
    private Integer lineId;

    /**
     * 绑定的相机ID
     */
    @TableField("camera_id")
    private Integer cameraId;
}
