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
 * 产品品牌维度表
 * </p>
 *
 * @author 45344
 * @since 2026-01-16
 */
@Getter
@Setter
@TableName("dim_product_brand")
public class DimProductBrand implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 品牌ID
     */
    @TableId(value = "brand_id", type = IdType.AUTO)
    private Integer brandId;

    /**
     * 品牌编码：QBB, SZWG, BC_SUMMER
     */
    @TableField("brand_code")
    private String brandCode;

    /**
     * 品牌中文名：亲宝宝、狮子王国等
     */
    @TableField("brand_name")
    private String brandName;
}
