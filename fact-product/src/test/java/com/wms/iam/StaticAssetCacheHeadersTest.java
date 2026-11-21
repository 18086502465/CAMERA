package com.wms.iam;

import com.wms.iam.infra.StaticAssetCacheHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态资源缓存头契约（2026-11-21 性能第二批 P0-2）。
 *
 * <p>冻结点：带内容哈希的 {@code /assets/**} 必须可长缓存；
 * 其它响应必须保持 Spring Security 原有的 no-store 语义，不能因开启静态缓存而放松安全头。
 */
class StaticAssetCacheHeadersTest {

    private final StaticAssetCacheHeaders writer = new StaticAssetCacheHeaders();

    @Test
    @DisplayName("/assets/** 返回一年 immutable 缓存头")
    void assetsUseImmutableLongCache() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/assets/index-abc123.js");
        MockHttpServletResponse res = new MockHttpServletResponse();

        writer.writeHeaders(req, res);

        String cc = res.getHeader("Cache-Control");
        assertTrue(cc.contains("public"), cc);
        assertTrue(cc.contains("max-age=31536000"), cc);
        assertTrue(cc.contains("immutable"), cc);
    }

    @Test
    @DisplayName("非静态响应仍是 no-store，安全语义不变")
    void otherResponsesStayNoStore() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/analytics/line-summary");
        MockHttpServletResponse res = new MockHttpServletResponse();

        writer.writeHeaders(req, res);

        String cc = res.getHeader("Cache-Control");
        assertTrue(cc.contains("no-store"), cc);
        assertTrue(cc.contains("no-cache"), cc);
        assertEquals("no-cache", res.getHeader("Pragma"));
    }
}
