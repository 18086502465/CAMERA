package com.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.SysOperationLog;
import com.wms.mapper.SysOperationLogMapper;
import com.wms.service.ISysOperationLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 系统操作日志 Service 实现类
 */
@Service
public class SysOperationLogServiceImpl extends ServiceImpl<SysOperationLogMapper, SysOperationLog> implements ISysOperationLogService {

    @Autowired
    private SysOperationLogMapper operationLogMapper;

    @Override
    public List<SysOperationLog> selectByDateRange(Date startTime, Date endTime) {
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(SysOperationLog::getOperationTime, startTime, endTime);
        wrapper.orderByDesc(SysOperationLog::getOperationTime);
        return list(wrapper);
    }

    @Override
    public List<SysOperationLog> selectByUserId(Integer userId) {
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysOperationLog::getUserId, userId);
        wrapper.orderByDesc(SysOperationLog::getOperationTime);
        return list(wrapper);
    }

    @Override
    public List<SysOperationLog> selectByModuleName(String moduleName) {
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(SysOperationLog::getModuleName, moduleName);
        wrapper.orderByDesc(SysOperationLog::getOperationTime);
        return list(wrapper);
    }

    @Override
    public int deleteOldLogs(int months) {
        return operationLogMapper.deleteOldLogs(months);
    }
}
