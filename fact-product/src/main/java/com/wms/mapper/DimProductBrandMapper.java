package com.wms.mapper;

import com.wms.entity.DimProductBrand;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 产品品牌维度表 Mapper 接口
 * </p>
 *
 * @author 45344
 * @since 2026-01-16
 */
@Mapper
public interface DimProductBrandMapper extends BaseMapper<DimProductBrand> {
    /**
     * 根据产品名称匹配品牌ID
     * @param productName 原始数据的product_name字段值
     * @return 匹配到的品牌主键brand_id，无匹配返回null
     */
    Integer selectBrandIdByProductName(@Param("productName") String productName);
}
