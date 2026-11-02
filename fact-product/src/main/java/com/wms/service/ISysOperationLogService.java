package com.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wms.entity.SysOperationLog;

import java.util.Date;
import java.util.List;

/**
 * 系统操作日志 Service 接口
 */
public interface ISysOperationLogService extends IService<SysOperationLog> {

    /**
     * 按日期范围查询日志
     */
    List<SysOperationLog> selectByDateRange(Date startTime, Date endTime);

    /**
     * 按用户ID查询日志
     */
    List<SysOperationLog> selectByUserId(Integer userId);

    /**
     * 按模块名称查询日志
     */
    List<SysOperationLog> selectByModuleName(String moduleName);

    /**
     * 删除指定月数前的日志
     */
    int deleteOldLogs(int months);
}
