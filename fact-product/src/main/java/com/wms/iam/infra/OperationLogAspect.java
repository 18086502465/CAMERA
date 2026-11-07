package com.wms.iam.infra;

import com.wms.iam.domain.model.SysOperationLog;
import com.wms.iam.app.ISysOperationLogService;
import com.wms.iam.domain.JwtUtil;
import lombok.extern.slf4j.Slf4j;
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
 * 自动记录所有 Controller 的<b>写操作</b>日志（PERF-01，2026-11-06 起 GET 一律不记，见
 * {@link #shouldSkipOperationLog}）
 */
@Slf4j
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
    @Around("execution(* com.wms.*.api..*(..))  // DDD 拆分：7 个业务域的 api 层")
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
                // 日志记录失败不影响业务（PERF-01：printStackTrace 会污染 stdout/journald，改为标准日志）
                log.warn("操作日志写入失败: {}", joinPoint.getSignature().toShortString(), e);
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
     * PERF-01（2026-11-06 改造）：<b>GET 请求一律不写操作日志</b>，只保留写操作（POST/PUT/DELETE）审计。
     *
     * <p>原因：切点拦截全部 Controller 方法，GET（含每张图片请求、30s 轮询）全部落库——
     * 相机状态页 80 张图 × 30s 轮询 ≈ 9600 行/小时/用户，把真实操作日志淹没、表无限膨胀。
     *
     * <p><b>前提（改此方法前必须复核）</b>：全仓 {@code @GetMapping} 不得有写语义端点
     * （2026-11-06 已核实：仅 ConfigImportController 的模板下载/导出为 GET，均属读操作）。
     * 若将来新增 GET 写端点，必须改为 POST 或在本方法显式豁免。
     *
     * <p>其余排除项维持 2026-11-02 口径：
     * <ul>
     *   <li>操作日志页面自身的查询/删除/清空不再写入，避免“删除后又新增一条日志”的自记录问题；</li>
     *   <li>服务器监控 5 秒轮询接口（否则每天 17280 条无意义记录）；</li>
     *   <li>设备心跳 POST（PERF-NEW-01，2026-11-06）：5s × 16 台 = 满载 27.6 万行/天，
     *       实测占审计日志 97%~98%。心跳状态由 {@code camera_status} 快照与告警表承接，
     *       审计日志不应记录遥测。与 server-metrics、图片 GET 同属“遥测/轮询端点必须
     *       显式排除审计”纪律——本类已是该反模式第三次出现，新增高频端点时先来此豁免。</li>
     * </ul>
     * 注意：登录/注册为 POST，<b>继续记录</b>（含失败记录，是 SEC-03 登录限速的数据基础）。
     */
    private boolean shouldSkipOperationLog(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        // 只审计写操作：GET（查询、图片加载、轮询）一律跳过
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        // 设备心跳遥测（5s 周期 POST，量级远超全部人工操作之和）
        if (uri.startsWith("/api/v1/camera/status/")) {
            return true;
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
