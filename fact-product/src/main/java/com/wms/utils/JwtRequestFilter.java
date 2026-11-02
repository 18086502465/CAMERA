package com.wms.utils;

import com.wms.entity.SysUser;
import com.wms.service.ISysUserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Key;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * JWT请求过滤器
 *
 * <p><b>关键修复（2026-10-28）</b>：此前本过滤器解析 Token、查用户、调用
 * {@code validateToken()} 全部做了，<b>唯独没有调用
 * {@code SecurityContextHolder.getContext().setAuthentication(...)}</b>，
 * 只打了一行"JWT Token 验证成功"的日志。日志看起来在鉴权，实际 SecurityContext 始终为空，
 * 配合 {@code SecurityConfig} 里约 60 条 {@code permitAll()} 白名单，
 * 导致<b>无 token 也能读取全部业务数据</b>（实测 {@code GET /api/cameras} 返回真实相机数据）。
 *
 * <p>现在补上了认证写入，并把"放行"与"认证"两件事分开：
 * <ul>
 *   <li><b>公开路径</b>：完全跳过 Token 解析（登录、静态资源、错误页）；</li>
 *   <li><b>设备路径</b>（{@code /api/v1/**}）：设备端用自己的 token 体系
 *       （{@code cfg_camera_token}），由 service 层校验，<b>不在此过滤器认证</b>
 *       —— 保持冻结契约 {@code docs/api/接口冻结契约-v1.0-20260616.md} 不变；</li>
 *   <li><b>其余路径</b>：必须有有效 JWT，并写入 SecurityContext。
 *       无 token 或 token 无效时<b>不直接拒绝</b>，而是交给 Spring Security
 *       按 {@code anyRequest().authenticated()} 决定 —— 这样
 *       {@code SecurityConfig} 的白名单仍然有效，白名单里的路径无需 token 也能访问。</li>
 * </ul>
 */
@Component
public class JwtRequestFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ISysUserService userService;

    /**
     * 公开路径 — 完全跳过 Token 解析。
     *
     * <p>注意：这里只放行<b>确实需要匿名访问</b>的路径。
     * 设备接口（/api/v1/**）不在此列表，而是走下面的「设备路径」分支。
     */
    private static final List<String> PUBLIC_PATHS = Arrays.asList(
        "/api/auth/login",
        "/api/auth/register",
        "/api/auth/me",
        "/api/camera/heartbeat",
        "/api/camera/image/**",
        "/swagger-ui/**",
        "/v3/api-docs/**",
        "/error"
    );

    /**
     * 设备路径 — 设备端用 {@code cfg_camera_token} 鉴权，由 service 层校验，
     * 走冻结契约，<b>不参与 JWT 认证</b>。
     *
     * <p><b>2026-10-31 服务器实测修正</b>：上一版这里是 {@code "/api/v1/**"}，
     * 结果整个 /api/v1/** 都不做 JWT 认证 —— 其中包含
     * {@code /api/v1/system/settings}、{@code /api/v1/config/**}、
     * {@code /api/v1/analytics/**} 等**管理端点**。
     * 配合 SecurityConfig 里同样宽泛的 {@code /api/v1/**} 白名单，
     * 实测匿名 PUT 系统设置、匿名 POST 产品配置都能改生产库。
     *
     * <p>现在改为**精确到方法与路径**，只放行冻结契约的 3 个设备端点。
     * 其余 /api/v1/** 走下面的 JWT 认证分支。
     */
    private static final List<String> DEVICE_PATHS = Arrays.asList(
        "/api/v1/camera/status/heartbeat",
        "/api/v1/camera/inspection/batch-upload",
        "/api/v1/defect-images/upload"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private boolean isPublicPath(String requestUri) {
        return PUBLIC_PATHS.stream().anyMatch(p -> pathMatcher.match(p, requestUri));
    }

    private boolean isDevicePath(String requestUri) {
        return DEVICE_PATHS.stream().anyMatch(p -> pathMatcher.match(p, requestUri));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        final String requestUri = request.getRequestURI();

        // 1) 公开路径 — 直接放行，不解析 Token
        if (isPublicPath(requestUri)) {
            chain.doFilter(request, response);
            return;
        }

        // 2) 设备路径 — 走设备 token 体系（service 层校验），不做 JWT 认证
        if (isDevicePath(requestUri)) {
            chain.doFilter(request, response);
            return;
        }

        // 3) 其余路径 — 解析 JWT；有效则写入 SecurityContext
        final String requestTokenHeader = request.getHeader("Authorization");

        String username = null;
        String jwtToken = null;

        if (requestTokenHeader != null && requestTokenHeader.startsWith("Bearer ")) {
            jwtToken = requestTokenHeader.substring(7);
            if (jwtToken != null && !jwtToken.isEmpty()) {
                try {
                    username = jwtUtil.getUsernameFromToken(jwtToken);
                } catch (Exception e) {
                    logger.warn("JWT Token 解析失败: " + e.getMessage());
                }
            }
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            SysUser user = userService.findByUsername(username);

            if (user != null && jwtUtil.validateToken(jwtToken)) {
                // ★ 关键：把认证结果写入 SecurityContext。
                //   缺了这一行，Spring Security 认为当前是匿名请求，
                //   白名单里的路径全都能匿名访问 —— 这就是之前的鉴权失效根因。
                Long userId = jwtUtil.getUserIdFromToken(jwtToken);
                Integer role = jwtUtil.getRoleFromToken(jwtToken);
                if (userId == null) {
                    userId = user.getUserId();
                }
                if (role == null) {
                    role = user.getRole();
                }
                UserDetails principal = new JwtUserDetails(user.getUsername(), user.getPasswordHash(), role, userId);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                logger.debug("JWT 认证成功，用户：" + username);
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * 由 JWT 构造的 UserDetails —— 不查库取权限，避免每次请求多一次 DB 往返。
     * 角色沿用数据模型里的 {@code role}：0=工人，1=管理员。
     */
    static class JwtUserDetails implements UserDetails {

        private final String username;
        private final String password;
        private final Integer role;
        private final Long userId;
        private final Collection<GrantedAuthority> authorities;

        JwtUserDetails(String username, String password, Integer role, Long userId) {
            this.username = username;
            this.password = password;
            this.role = role;
            this.userId = userId;
            List<GrantedAuthority> list = new ArrayList<>();
            list.add(new SimpleGrantedAuthority(role != null && role == 1 ? "ROLE_ADMIN" : "ROLE_USER"));
            this.authorities = Collections.unmodifiableList(list);
        }

        public Long getUserId() {
            return userId;
        }

        public Integer getRole() {
            return role;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return authorities;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public boolean isAccountNonExpired() {
            return true;
        }

        @Override
        public boolean isAccountNonLocked() {
            return true;
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return true;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
