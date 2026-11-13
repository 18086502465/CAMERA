package com.wms.system.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.shared.license.LicenseState;
import com.wms.shared.util.LogThrottle;
import com.wms.system.app.LicenseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 授权闸门：把 {@link LicenseService} 的判定落到请求上。
 *
 * <p><b>为什么是 Interceptor 而不是 Filter</b>：第一版写成
 * {@code @Component + OncePerRequestFilter}，Tomcat 启动时报
 * {@code Exception starting filter [licenseGuardFilter] → NullPointerException
 * at GenericFilterBean.init:241}，服务起不来、部署直接回滚。
 * {@code GenericFilterBean} 的 {@code init} 在 Spring Boot 自动注册路径上依赖
 * 容器注入的上下文状态，踩坑成本远高于收益。MVC Interceptor 没有这个生命周期，
 * 拿到的请求/响应能力完全相同，所以选它。
 *
 * <p>两级拦截，都只在 {@code expired-mode} 为 {@code readonly}/{@code block}
 * 且已过宽限期时才生效（{@code warn} 模式下永远放行）：
 * <ul>
 *   <li><b>设备写入</b>（readonly 起）：上传 / 心跳 / batch-upload 三个 POST；</li>
 *   <li><b>全站</b>（仅 block）：除登录、授权页、告警查询外的所有 {@code /api/**}。</li>
 * </ul>
 *
 * <p><b>为什么用 HTTP 200 + body code=503</b>：设备端固件按 body 里的
 * {@code code/message} 判断结果，改 HTTP 状态码会让它们当成网络故障无限重试。
 */
@Component
@Slf4j
public class LicenseGuardInterceptor implements HandlerInterceptor {

    private static final String CODE_BLOCKED = "LICENSE_BLOCKED";
    private static final String CODE_EXPIRED = "LICENSE_EXPIRED";

    private static final String[] DEVICE_WRITE_PATHS = new String[]{
            "/api/v1/defect-images/upload",
            "/api/v1/camera/status/heartbeat",
            "/api/v1/camera/inspection/batch-upload"
    };

    /** block 模式下仍然放行的路径。 */
    private static final String[] ALWAYS_ALLOWED = new String[]{
            "/api/license",
            "/api/auth/login",
            "/api/auth/me",
            "/api/alerts/active"
    };

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private LicenseService licenseService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String uri = request.getRequestURI();
        if (uri == null) {
            return true;
        }

        LicenseState state = licenseService.getState();
        if (!state.isWriteBlocked()) {
            return true;
        }

        if ("POST".equalsIgnoreCase(request.getMethod()) && isDeviceWrite(uri)) {
            String msg = "授权" + state.getStatus().getLabel() + "（" + state.getMessage()
                    + "），已暂停接收设备上报；请联系管理员续期授权后恢复。";
            writeResult(response, CODE_EXPIRED, msg);
            // 心跳 5 秒/台，不节流会把日志刷满（与 token 告警同一策略）
            if (LogThrottle.allow("license-blocked-device", 10 * 60 * 1000L)) {
                log.warn("授权失效，已拒绝设备写入: uri={} —— {}", uri, msg);
            }
            return false;
        }

        // readonly 模式只拦设备写入，管理端照常可用
        if (!licenseService.isAccessBlocked()) {
            return true;
        }

        for (String allowed : ALWAYS_ALLOWED) {
            if (uri.startsWith(allowed)) {
                return true;
            }
        }
        writeResult(response, CODE_BLOCKED,
                "授权" + state.getStatus().getLabel() + "（" + state.getMessage()
                        + "）。系统已进入受限模式，仅保留登录与授权管理功能。");
        return false;
    }

    private boolean isDeviceWrite(String uri) {
        for (String p : DEVICE_WRITE_PATHS) {
            if (p.equals(uri)) {
                return true;
            }
        }
        return false;
    }

    private void writeResult(HttpServletResponse response, String errorCode, String message)
            throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 503);
        body.put("errorCode", errorCode);
        body.put("message", message);
        body.put("data", null);
        response.getWriter().write(JSON.writeValueAsString(body));
    }
}
