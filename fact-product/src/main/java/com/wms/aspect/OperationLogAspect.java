package com.wms.aspect;

import com.wms.entity.SysOperationLog;
import com.wms.service.ISysOperationLogService;
import com.wms.utils.JwtUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;

/**
 * 操作日志 AOP 切面
 * 自动记录所有 Controller 的操作日志
 */
@Aspect
@Component
public class OperationLogAspect {

    @Autowired
    private ISysOperationLogService operationLogService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 环绕通知：拦截所有 Controller 方法
     */
    @Around("execution(* com.wms.controller..*(..))")
    public Object logOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        Object result = null;
        boolean success = true;
        String errorMessage = null;

        try {
            result = joinPoint.proceed();
        } catch (Exception e) {
            success = false;
            String msg = e.getMessage();
            errorMessage = msg != null && msg.length() > 500 ? msg.substring(0, 500) : msg;
            throw e;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            try {
                saveOperationLog(joinPoint, executionTime, success, errorMessage);
            } catch (Exception e) {
                // 日志记录失败不影响业务
                e.printStackTrace();
            }
        }

        return result;
    }

    /**
     * 保存操作日志
     */
    private void saveOperationLog(ProceedingJoinPoint joinPoint, long executionTime, boolean success, String errorMessage) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return;
        }
        HttpServletRequest request = attributes.getRequest();
        if (shouldSkipOperationLog(request)) {
            return;
        }

        SysOperationLog log = new SysOperationLog();
        log.setOperationTime(new Date());
        log.setExecutionTime((int) executionTime);
        log.setOperationResult(success ? 1 : 0);
        log.setErrorMessage(errorMessage);

        // 获取当前用户
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                String token = authHeader.substring(7);
                Long userId = jwtUtil.getUserIdFromToken(token);
                String username = jwtUtil.getUsernameFromToken(token);
                log.setUserId(Math.toIntExact(userId));
                log.setUserName(username);
            } catch (Exception e) {
                log.setUserId(0);
                log.setUserName("未知");
            }
        } else {
            log.setUserId(0);
            log.setUserName("匿名");
        }

        // 操作类型
        String methodName = joinPoint.getSignature().getName();
        String operationType = getOperationType(methodName);
        log.setOperationType(operationType);

        // 模块名称
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String moduleName = getModuleName(className);
        log.setModuleName(moduleName);

        // 请求信息
        log.setRequestMethod(request.getMethod());
        log.setRequestUri(request.getRequestURI());
        log.setIpAddress(getIpAddress(request));
        log.setRequestParams(getRequestParams(request));

        // 操作描述
        log.setOperationDesc(moduleName + " - " + operationType);

        operationLogService.save(log);
    }

    /**
     * 根据方法名判断操作类型
     */
    private String getOperationType(String methodName) {
        if (methodName.startsWith("login") || methodName.startsWith("register")) {
            return "LOGIN";
        } else if (methodName.startsWith("save") || methodName.startsWith("create") || methodName.startsWith("add")) {
            return "CREATE";
        } else if (methodName.startsWith("update") || methodName.startsWith("edit")) {
            return "UPDATE";
        } else if (methodName.startsWith("delete") || methodName.startsWith("remove")) {
            return "DELETE";
        } else if (methodName.startsWith("export")) {
            return "EXPORT";
        } else {
            return "QUERY";
        }
    }

    /**
     * 操作日志页面本身的查询、删除、清空不再写入操作日志，避免出现“删除后又新增一条日志”
     * 或“清空后马上又出现一条查询/清空日志”的自记录问题。
     *
     * <p><b>2026-11-02 新增：服务器监控轮询接口也必须排除。</b>
     * 前端以 5 秒周期轮询 {@code GET /api/v1/system/server/metrics}，
     * 若不排除，每天会往 {@code sys_operation_log} 写入
     * 86400 / 5 = <b>17280 条</b>无意义记录，
     * 把真正的操作日志淹没，还会让这张表以每天 1.7 万行的速度膨胀。
     */
    private boolean shouldSkipOperationLog(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        // 操作日志自身的查询/清理
        if (uri.startsWith("/api/logs/operation")) {
            return true;
        }
        // 服务器监控的高频轮询接口
        if (uri.startsWith("/api/v1/system/server/")) {
            return true;
        }
        return false;
    }

    /**
     * 根据类名判断模块名称
     */
    private String getModuleName(String className) {
        if (className.contains("User")) {
            return "用户管理";
        } else if (className.contains("Company")) {
            return "公司管理";
        } else if (className.contains("Factory")) {
            return "工厂管理";
        } else if (className.contains("Line") || className.contains("ProductionLine")) {
            return "产线管理";
        } else if (className.contains("Workstation")) {
            return "工位管理";
        } else if (className.contains("Brand")) {
            return "品牌管理";
        } else if (className.contains("Defect")) {
            return "缺陷分类管理";
        } else if (className.contains("Camera")) {
            return "相机管理";
        } else if (className.contains("Detection") || className.contains("Production")) {
            return "检测数据管理";
        } else if (className.contains("Anomaly") || className.contains("Alert")) {
            return "异常告警管理";
        } else if (className.contains("Holiday")) {
            return "节假日配置";
        } else if (className.contains("Stats") || className.contains("Dashboard")) {
            return "统计报表";
        } else {
            return "系统管理";
        }
    }

    /**
     * 获取请求参数
     */
    private String getRequestParams(HttpServletRequest request) {
        try {
            String params = request.getQueryString();
            if (params != null) {
                return params.length() > 500 ? params.substring(0, 500) : params;
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 获取客户端 IP 地址
     */
    private String getIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
