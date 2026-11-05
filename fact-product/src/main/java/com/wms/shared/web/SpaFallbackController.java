package com.wms.shared.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Vue history 模式路由兜底。
 *
 * 前端 dist 会被打进 Spring Boot 的 static 目录。用户直接访问
 * /camera-status、/system/config、/stats/images 等 Vue 路由时，后端需要返回
 * index.html；但 /api、Swagger、静态文件请求不能被兜底吞掉。
 */
@Controller
public class SpaFallbackController {

    @GetMapping(value = {
            "/{path:[^\\.]*}",
            "/**/{path:[^\\.]*}"
    })
    public String forwardToIndex(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String uri = request.getRequestURI();
        if (isBackendOrStaticRequest(uri)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        return "forward:/index.html";
    }

    private boolean isBackendOrStaticRequest(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.startsWith("/api/")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/actuator")
                || uri.startsWith("/assets/")
                || uri.startsWith("/error");
    }
}
