package com.wms.iam.infra;

import com.wms.iam.infra.JwtRequestFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;

/**
 * Spring Security 配置类
 * - 注册 JWT 过滤器
 * - 配置放行规则（登录/注册接口免认证）
 * - 禁用 CSRF、开启 CORS
 *
 * <p><b>2026-10-31 服务器实测修正</b>：
 * <ol>
 *   <li>白名单不再整体放行 {@code /api/v1/**}。实测发现该前缀下混着管理端点
 *       （系统设置、产品配置、缺陷类型、映射、看板），匿名即可读写生产库。
 *       现在只精确放行冻结契约的 3 个设备端点 + 图片静态读取。</li>
 *   <li>启用方法级鉴权（{@code @EnableGlobalMethodSecurity}），
 *       配合控制器上的 {@code @PreAuthorize("hasRole('ADMIN')")} 做双保险，
 *       防止将来新增路径时只靠 antMatchers 漏配。</li>
 * </ol>
 */
@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Autowired
    private JwtRequestFilter jwtRequestFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf().disable()
            .cors().configurationSource(corsConfigurationSource())
            .and()
            .authorizeRequests()
                // ==============================================================
                // 白名单（2026-10-31 服务器实测后再次收紧）
                // ==============================================================
                //
                // ⚠️ 本次收紧的原因（实机实测复现，非纸面推演）：
                //
                //   上一版把整个 "/api/v1/**" 放进白名单，理由是
                //   「设备端用自己的 token 体系（cfg_camera_token）」。
                //   **但 /api/v1/** 下面不只有设备端点，还有大量管理端点**：
                //     /api/v1/system/settings     系统设置（GET + PUT）
                //     /api/v1/config/products      产品配置（增删改查）
                //     /api/v1/config/defect-types  缺陷类型（增删改查）
                //     /api/v1/config/camera-defect-mappings  缺陷映射（增删改查）
                //     /api/v1/config/mapping-exceptions      映射异常（可关闭）
                //     /api/v1/analytics/**         看板与部件（增删改查）
                //
                //   实测（2026-10-31，服务器 10.2.77.72）：
                //     匿名 PUT  /api/v1/system/settings
                //       → HTTP 200「系统设置已保存」→ 查库确认 alert.cpuWarning
                //         从 90 被改成 77（**匿名真的改了生产库**）
                //     匿名 POST /api/v1/config/products
                //       → HTTP 200「创建成功」→ 查库确认 dim_product 从 2 条变 3 条
                //     匿名 GET  /api/v1/config/products、/defect-types
                //       → HTTP 200（配置数据完全泄露）
                //
                //   也就是说：**只要能访问到 8091 端口，不需要任何凭据，
                //   就能修改生产配置、删除缺陷类型、读取全部配置数据。**
                //   这比之前修掉的「匿名注册管理员」更严重 —— 那个至少要先注册账号。
                //
                //   **修复方式**：白名单改为**精确到方法与路径**，
                //   只放行冻结契约里的 3 个设备端点 + 图片静态读取，
                //   其余 /api/v1/** 一律要求登录，并按角色区分。
                //
                //   设备端 3 个冻结端点（契约见 docs/api/接口冻结契约-v1.0-20260616.md）：
                //     POST /api/v1/camera/status/heartbeat
                //     POST /api/v1/camera/inspection/batch-upload
                //     POST /api/v1/defect-images/upload
                //   它们由 service 层用 cfg_camera_token 校验（见 InspectionUploadServiceImpl），
                //   实测带错 token 返回 422「设备 Token 无效或已过期」，校验本身是有效的。
                //
                //   图片静态读取（/api/v1/defect-images/file/**、/api/v1/camera/image/**）
                //   必须匿名可访问 —— 前端 <img src> 标签不会带 Authorization 头，
                //   一旦要求 token，前端所有缺陷图片都会裂图。
                //   这里用的是不可猜测的路径（日期/相机/毫秒时间戳+uuid8），
                //   且文件里不含用户凭据，暴露面可接受。
                //
                // ⚠️ 不要再把业务查询路径放进白名单 —— 那些需要登录才能看的数据。
                //   之前的版本把这些全放进来了，加上 JwtRequestFilter 从不写入
                //   SecurityContext，等于完全无鉴权。

                // ---- 前端 SPA 静态资源 ----
                .antMatchers(
                    "/",
                    "/index.html",
                    "/favicon.ico",
                    "/assets/**",
                    "/*.js",
                    "/*.css",
                    "/*.png",
                    "/*.jpg",
                    "/*.jpeg",
                    "/*.svg",
                    "/*.ico"
                ).permitAll()
                // ---- 前端 SPA 深链（history 模式路由）----
                // 浏览器直接访问 /dashboard、/system/config 等路径时，
                // 服务端要先返回 index.html，此时用户可能还没登录、没有 token。
                // 不放行的话，用户一刷新页面就 403 —— 前端连登录页都进不去。
                // 这里放行的是**页面路由**（返回 HTML），不是数据接口；
                // 页面里的数据请求由前端带 token 走 /api/**，仍然受鉴权保护。
                .antMatchers(
                    "/login",
                    "/register",
                    "/dashboard",
                    "/system-config",
                    "/system/config",
                    "/camera-status",
                    "/cameras",
                    "/cameras/**",
                    "/detection",
                    "/anomaly",
                    "/stats/**",
                    "/line-analytics",
                    "/companies",
                    "/factories",
                    "/lines",
                    "/workstations",
                    "/users",
                    "/brands",
                    "/defect-types",
                    "/holidays",
                    "/operation-logs",
                    "/profile",
                    "/defects/**",
                    // 服务器监控页（2026-11-02 新增）
                    // ⚠️ 必须加进 SPA 页面路由白名单，否则浏览器直接访问该 URL
                    //    刷新时会 403 —— 服务端要先返回 index.html，
                    //    而此时用户可能还没登录、没有 token。
                    //    页面里的数据请求走 /api/v1/system/server/**，仍受鉴权保护。
                    "/server-monitor",
                    "/server-monitor/**"
                ).permitAll()
                // ---- 认证相关 ----
                // /api/auth/register 保留匿名：注册角色已固定为普通用户(0)，
                // 提权路径已堵死。是否关闭自助注册需业务方决策（见评审报告）。
                .antMatchers(
                    "/api/auth/login",
                    "/api/auth/register",
                    "/api/auth/me"
                ).permitAll()
                // ---- 设备端冻结契约 3 端点（service 层校验 cfg_camera_token）----
                .antMatchers(HttpMethod.POST,
                    "/api/v1/camera/status/heartbeat",
                    "/api/v1/camera/inspection/batch-upload",
                    "/api/v1/defect-images/upload"
                ).permitAll()
                // ---- 图片静态读取（前端 <img> 不带 Authorization 头，必须放行）----
                .antMatchers(HttpMethod.GET,
                    "/api/v1/defect-images/file/**",
                    "/api/v1/camera/image/**"
                ).permitAll()
                // ---- 文档与错误页 ----
                .antMatchers(
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/error"
                ).permitAll()

                // ==============================================================
                // 管理员专用端点 —— 必须 ROLE_ADMIN
                // ==============================================================
                //
                // 实测（2026-10-31）：上一版没有任何角色限制，
                // 实测「注册一个 role=0 的普通用户 → 拿它的 token →
                // DELETE /api/user/3 → HTTP 200「删除成功」→ 查库确认 admin 被删了」。
                // 也就是说**任何登录用户都能删掉管理员账号**。
                //
                // JwtUserDetails 已经构造好了 ROLE_ADMIN / ROLE_USER 权限，
                // 只是 SecurityConfig 从来没用过它（filter 里备好了没人引）。
                //
                // 原则：**读开放给所有登录用户，写只给管理员。**
                // 现场工人也要看看板、缺陷记录、相机状态、统计，
                // 这些查询接口不能挡；增删改必须挡住。
                .antMatchers(
                    // ---- 用户与日志（纯管理，全段 ADMIN）----
                    "/api/user/**",
                    "/api/logs/operation/**"
                ).hasRole("ADMIN")

                // ---- 组织架构与配置的「写」操作 ----
                .antMatchers(HttpMethod.POST,
                    "/api/cameras/**",
                    "/api/factories/**",
                    "/api/lines/**",
                    "/api/workstations/**",
                    "/api/company/**",
                    "/api/holiday/**",
                    "/api/dim-product-brand/**",
                    "/api/dim-shift/**",
                    "/api/fact/**",
                    "/api/fact-production-info/**",
                    "/api/DimTimeBucket30min/**",
                    "/api/v1/config/**",
                    "/api/v1/analytics/**",
                    "/api/v1/system/**",
                    // SEC-02：DimTimeBucket30minController 真实映射无 /api 前缀，
                    // 上面的 /api/DimTimeBucket30min/** 是匹配不到的历史死规则（P2 清理）。
                    // 前端已无调用方；写操作 ADMIN，与控制器 @PreAuthorize 双保险。
                    "/DimTimeBucket30min/**"
                ).hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT,
                    "/api/cameras/**",
                    "/api/factories/**",
                    "/api/lines/**",
                    "/api/workstations/**",
                    "/api/company/**",
                    "/api/holiday/**",
                    "/api/dim-product-brand/**",
                    "/api/dim-shift/**",
                    "/api/fact/**",
                    "/api/fact-production-info/**",
                    "/api/DimTimeBucket30min/**",
                    "/api/v1/config/**",
                    "/api/v1/analytics/**",
                    "/api/v1/system/**",
                    // SEC-02：无 /api 前缀的真实路径（同上）
                    "/DimTimeBucket30min/**"
                ).hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE,
                    "/api/cameras/**",
                    "/api/factories/**",
                    "/api/lines/**",
                    "/api/workstations/**",
                    "/api/company/**",
                    "/api/holiday/**",
                    "/api/dim-product-brand/**",
                    "/api/dim-shift/**",
                    "/api/fact/**",
                    "/api/fact-production-info/**",
                    "/api/DimTimeBucket30min/**",
                    "/api/v1/config/**",
                    "/api/v1/analytics/**",
                    "/api/v1/system/**",
                    // SEC-02：无 /api 前缀的真实路径（同上）
                    "/DimTimeBucket30min/**",
                    "/api/detection-history/clean-history"
                ).hasRole("ADMIN")
                // 配置导入导出（批量改配置，绝不能给普通用户）
                .antMatchers("/api/config-import/**").hasRole("ADMIN")

                // ==============================================================
                // 其余业务查询 —— 登录即可（ROLE_USER 与 ROLE_ADMIN 都可以）
                // ==============================================================
                // 看板、统计、缺陷记录、图片记录、相机列表、组织架构下拉、
                // 节假日查询、缺陷类型查询等只读接口，现场工人也要看。
                .antMatchers(
                    "/api/stats/**",
                    "/api/cameras/**",
                    "/api/factories/**",
                    "/api/lines/**",
                    "/api/workstations/**",
                    "/api/company/**",
                    "/api/cascade/**",
                    "/api/holiday/**",
                    "/api/alerts/**",
                    "/api/detection-history/**",
                    "/api/dim-product-brand/**",
                    "/api/dim-shift/**",
                    "/api/fact/**",
                    "/api/fact-production-info/**",
                    "/api/DimTimeBucket30min/**",
                    "/api/v1/config/**",
                    "/api/v1/system/**",
                    "/api/v1/analytics/**",
                    "/api/v1/defect-images/recent",
                    "/api/v1/defect-images/history",
                    "/api/v1/defect-images/camera-summaries",
                    "/api/v1/camera/images/**"
                ).authenticated()

                // 其余一律要求登录
                .anyRequest().authenticated()
            .and()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            // ==============================================================
            // ★ 2026-11-04：区分「未认证」与「权限不足」
            // ==============================================================
            // 实测发现（服务器 10.2.77.72）：
            //   无 token / 伪造 token 访问需认证端点 → **403**
            //   已认证但角色不足 → **也是 403**
            //   两者的响应体完全相同：{"status":403,"error":"Forbidden","path":"/api/..."}
            //   且响应头里**没有 WWW-Authenticate**（Spring Security 2.7 默认行为）。
            //
            //   后果：前端拿到 403 无法判断该跳登录页还是提示"权限不足"，
            //   只能一律跳登录 —— 管理员点了个 ADMIN 端点就被踢出登录，误打断操作。
            //
            //   现在按 Spring Security 的标准语义拆开：
            //   - AuthenticationEntryPoint：**未认证**（无 token / token 无效 / token 过期）
            //     → 401 Unauthorized，body 带 reason=AUTHENTICATION_REQUIRED
            //   - AccessDeniedHandler：**已认证但权限不足**（角色不匹配）
            //     → 403 Forbidden，body 带 reason=ACCESS_DENIED
            //
            //   前端据此精确分流：401 与 403+未登录 → 跳登录；403+权限不足 → 只提示不跳转。
            .exceptionHandling()
                .authenticationEntryPoint(restAuthenticationEntryPoint())
                .accessDeniedHandler(restAccessDeniedHandler())
            .and();

        // 将 JWT 过滤器添加到 UsernamePasswordAuthenticationFilter 之前
        http.addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 未认证处理器：返回 **401** + reason=AUTHENTICATION_REQUIRED。
     *
     * <p>Spring Security 2.7 默认对未认证请求返回 403（因为没有配置 WWW-Authenticate），
     * 与「权限不足」的 403 无法区分。这里显式改为 401 并带上语义标记。
     */
    @Bean
    public AuthenticationEntryPoint restAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":401,\"message\":\"登录状态已失效，请重新登录\","
                            + "\"reason\":\"AUTHENTICATION_REQUIRED\",\"path\":\""
                            + safePath(request.getRequestURI()) + "\"}");
        };
    }

    /**
     * 权限不足处理器：返回 **403** + reason=ACCESS_DENIED。
     *
     * <p>与未认证区分开，前端据此**不跳转**、只提示，避免打断管理员的正常操作。
     */
    @Bean
    public AccessDeniedHandler restAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":403,\"message\":\"没有访问权限，请使用具备相应角色的账号登录\","
                            + "\"reason\":\"ACCESS_DENIED\",\"path\":\""
                            + safePath(request.getRequestURI()) + "\"}");
        };
    }

    /**
     * 回写响应体时避免 JSON 注入（路径里理论上不会有引号，但不做无谓的风险假设）。
     */
    private static String safePath(String path) {
        if (path == null) return "";
        return path.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(Arrays.asList("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
