package com.wms.analytics.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.analytics.domain.model.FactProduction30minArchive;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

/**
 * 生产数据归档表 Mapper
 */
public interface FactProduction30minArchiveMapper extends BaseMapper<FactProduction30minArchive> {

    /**
     * 按日期范围查询
     */
    @Select("SELECT * FROM fact_production_30min_archive WHERE bucket_date BETWEEN #{startDate} AND #{endDate} ORDER BY bucket_date, bucket_index")
    List<FactProduction30minArchive> selectByDateRange(@Param("startDate") Date startDate, @Param("endDate") Date endDate);

    /**
     * 按产线ID查询
     */
    @Select("SELECT * FROM fact_production_30min_archive WHERE line_id = #{lineId} ORDER BY bucket_date DESC, bucket_index")
    List<FactProduction30minArchive> selectByLineId(@Param("lineId") Integer lineId);
}
