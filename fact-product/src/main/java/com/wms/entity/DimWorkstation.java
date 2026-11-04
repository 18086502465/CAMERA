package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableLogic;
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

    /**
     * 是否启用
     */
    @TableField("is_enabled")
    private Integer isEnabled;

    /**
     * 逻辑删除标记。
     *
     * <p><b>2026-11-04 修正</b>：{@code dim_workstation} 表<b>本来就有</b> {@code is_deleted} 列，
     * 但本实体类<b>从未映射</b>它，也没有 {@code @TableLogic} 注解 ——
     * 于是 {@code workstationService.removeById(id)} 执行的是<b>物理删除</b>，
     * 工位一旦删除就<b>无法恢复</b>，且与产线/工厂/相机的逻辑删除行为不一致。
     *
     * <p>补上映射后，工位删除与其它维度表统一为<b>逻辑删除</b>。
     */
    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;
}
