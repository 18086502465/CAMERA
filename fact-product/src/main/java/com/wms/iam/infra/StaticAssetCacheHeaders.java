package com.wms.iam.infra;

import org.springframework.security.web.header.HeaderWriter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 区分「带内容哈希的静态资源」与「其它响应」的缓存头（2026-11-21 性能第二批 P0-2）。
 *
 * <p><b>为什么需要</b>：Spring Security 默认对所有响应写
 * {@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate}。
 * 现场实测 {@code /assets/index-*.js}（1.8MB）每次刷新都全量重下，{@code transferSize} 不变，
 * 是刷新页 FCP 1.7s 的主因之一。
 *
 * <p><b>为什么对 assets 敢用一年 immutable</b>：
 * <ol>
 *   <li>Vite 产物文件名带内容哈希，内容变则文件名变；</li>
 *   <li>{@code build-release.sh} 保留最近 3 版历史 chunk，旧路由不会 404（踩坑速查 §16）；</li>
 *   <li>{@code index.html} 本身仍走 no-store，新版本入口不会被缓存卡住。</li>
 * </ol>
 *
 * <p><b>接入方式</b>：在 {@link SecurityConfig} 里
 * {@code .headers().cacheControl().disable().addHeaderWriter(new StaticAssetCacheHeaders())}。
 * 禁用的是 Spring 的默认 writer，本类对非 assets 响应写回**完全相同**的安全头，
 * 不削弱其它端点的 no-store 语义。
 */
public class StaticAssetCacheHeaders implements HeaderWriter {

    private static final long ONE_YEAR_SECONDS = 31536000L;

    @Override
    public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/assets/")) {
            response.setHeader("Cache-Control", "public, max-age=" + ONE_YEAR_SECONDS + ", immutable");
            response.setDateHeader("Expires", System.currentTimeMillis() + ONE_YEAR_SECONDS * 1000L);
        } else {
            response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        }
    }
}
