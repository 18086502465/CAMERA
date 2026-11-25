package com.wms.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H5（2026-11-25）：告警解决端点是写操作，必须有 ADMIN 角色门槛。
 *
 * <p>原实现只靠 SecurityConfig 的 {@code /api/alerts/**.authenticated()}，
 * 任意登录用户都能把告警标记解决——读角色可以抹掉运维/业务故障证据。
 */
class AlertResolveAuthorizationTest {

    @Test
    @DisplayName("resolveAlert 必须标注 @PreAuthorize(hasRole('ADMIN'))")
    void resolveAlertRequiresAdmin() throws Exception {
        Class<?> clazz = Class.forName("com.wms.analytics.api.FactAnomalyAlertController");
        Method m = clazz.getDeclaredMethod("resolveAlert", Long.class);
        PreAuthorize ann = m.getAnnotation(PreAuthorize.class);
        assertNotNull(ann, "resolveAlert 缺少 @PreAuthorize：任意登录用户可抹掉告警");
        assertTrue(ann.value().contains("hasRole('ADMIN')"),
                "resolveAlert 的 @PreAuthorize 必须是 ADMIN 角色门槛，实际=" + ann.value());
    }

    @Test
    @DisplayName("只读端点 /active 不应被误加 ADMIN 门槛")
    void activeQueryStaysAuthenticatedOnly() throws Exception {
        Class<?> clazz = Class.forName("com.wms.analytics.api.FactAnomalyAlertController");
        Method m = clazz.getDeclaredMethod("getActiveAlerts");
        assertNull(m.getAnnotation(PreAuthorize.class),
                "active 是只读查询，保持 authenticated 即可，不应阻断只读用户");
    }
}
