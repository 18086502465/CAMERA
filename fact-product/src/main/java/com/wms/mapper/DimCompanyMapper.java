package com.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.entity.DimCompany;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface DimCompanyMapper extends BaseMapper<DimCompany> {

    @Select("SELECT * FROM dim_company WHERE company_name = #{companyName} AND is_deleted = 0")
    DimCompany findByCompanyName(@Param("companyName") String companyName);

    @Select("SELECT * FROM dim_company WHERE company_code = #{companyCode} AND is_deleted = 0")
    DimCompany findByCompanyCode(@Param("companyCode") String companyCode);
}
