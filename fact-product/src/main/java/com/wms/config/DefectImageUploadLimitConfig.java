package com.wms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 缺陷图片上传数量前置限制。
 *
 * 说明：
 * 1. multipart 文件参数被 Controller 绑定时，Servlet 容器通常已经开始解析文件流；
 * 2. 因此为了“超过数量不接收完整图片流”，设备端/调用方应在请求头声明图片数量；
 * 3. 本过滤器在 multipart 解析之前读取数量头，超过上限时立即返回 413。
 *
 * 推荐请求头：X-Image-Count: <本批图片数量>
 * 兼容请求头：X-File-Count / X-Upload-Image-Count
 */
@Configuration
public class DefectImageUploadLimitConfig {

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> defectImageUploadLimitFilter(
            @Value("${defect-image.max-batch-images:100}") int maxBatchImages) {
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new DefectImageUploadLimitFilter(maxBatchImages));
        bean.addUrlPatterns("/api/v1/defect-images/upload");
        bean.setName("defectImageUploadLimitFilter");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    private static class DefectImageUploadLimitFilter extends OncePerRequestFilter {
        private final int maxBatchImages;

        private DefectImageUploadLimitFilter(int maxBatchImages) {
            this.maxBatchImages = maxBatchImages;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            if (!"POST".equalsIgnoreCase(request.getMethod())) {
                filterChain.doFilter(request, response);
                return;
            }

            Integer declaredCount = firstIntHeader(request,
                    "X-Image-Count",
                    "X-File-Count",
                    "X-Upload-Image-Count");

            if (declaredCount != null && declaredCount > maxBatchImages) {
                writeTooManyImages(response, declaredCount);
                return;
            }

            filterChain.doFilter(request, response);
        }

        private Integer firstIntHeader(HttpServletRequest request, String... names) {
            for (String name : names) {
                String value = request.getHeader(name);
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        private void writeTooManyImages(HttpServletResponse response, int declaredCount) throws IOException {
            String message = "单批次最多支持 " + maxBatchImages + " 张图片，当前请求声明 " + declaredCount + " 张；请拆分为多个批次后重试";
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":500,\"message\":\"" + escapeJson(message) + "\",\"data\":null}");
        }

        private String escapeJson(String text) {
            return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
