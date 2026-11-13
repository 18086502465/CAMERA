package com.wms.system.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册授权闸门拦截器（2026-11-12）。
 *
 * <p>只挂 {@code /api/**}：页面路由与静态资源必须始终可达 —— 否则授权过期后
 * 连登录页和授权页都打不开，客户只能 SSH 进去手工换文件。
 */
@Configuration
public class LicenseWebConfig implements WebMvcConfigurer {

    @Autowired
    private LicenseGuardInterceptor licenseGuardInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(licenseGuardInterceptor).addPathPatterns("/api/**");
    }
}
