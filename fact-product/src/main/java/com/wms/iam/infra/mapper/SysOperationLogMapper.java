package com.wms.iam.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.iam.domain.model.SysOperationLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

public interface SysOperationLogMapper extends BaseMapper<SysOperationLog> {

    /**
     * 删除指定月数前的日志
     * @return 删除的记录数
     */
    @Delete("DELETE FROM sys_operation_log WHERE operation_time < DATE_SUB(NOW(), INTERVAL #{months} MONTH)")
    int deleteOldLogs(@Param("months") int months);

    /**
     * 按日期范围查询日志
     */
    @Select("SELECT * FROM sys_operation_log WHERE operation_time BETWEEN #{startTime} AND #{endTime} ORDER BY operation_time DESC")
    List<SysOperationLog> selectByDateRange(@Param("startTime") Date startTime, @Param("endTime") Date endTime);
}
