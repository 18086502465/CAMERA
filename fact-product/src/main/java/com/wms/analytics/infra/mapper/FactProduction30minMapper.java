package com.wms.analytics.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.analytics.domain.model.FactProduction30min;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 生产数据30分钟聚合表 Mapper
 */
public interface FactProduction30minMapper extends BaseMapper<FactProduction30min> {

    /**
     * 判断该时间桶+工位是否已有聚合数据
     */
    @Select("SELECT COUNT(*) FROM fact_production_30min " +
            "WHERE bucket_date = #{bucketDate} AND bucket_index = #{bucketIndex} " +
            "AND workstation_id = #{workstationId}")
    Integer countByTimeBucketAndStation(@Param("bucketDate") LocalDate bucketDate,
                                          @Param("bucketIndex") Integer bucketIndex,
                                          @Param("workstationId") Integer workstationId);

    /**
     * 查询指定月数之前的数据（用于归档）
     */
    @Select("SELECT * FROM fact_production_30min " +
            "WHERE bucket_date < DATE_SUB(CURDATE(), INTERVAL #{months} MONTH) " +
            "ORDER BY bucket_date, bucket_index")
    List<FactProduction30min> selectDataOlderThanMonths(@Param("months") int months);

    /**
     * 删除指定月数之前的数据（用于归档后清理）
     */
    @Delete("DELETE FROM fact_production_30min " +
            "WHERE bucket_date < DATE_SUB(CURDATE(), INTERVAL #{months} MONTH)")
    int deleteDataOlderThanMonths(@Param("months") int months);
}
