package com.wms.iam.api;

import com.wms.iam.domain.model.SysUser;
import com.wms.iam.app.ISysUserService;
import com.wms.iam.domain.ClientIpResolver;
import com.wms.iam.domain.JwtUtil;
import com.wms.iam.domain.LoginRateLimiter;
import com.wms.shared.common.ApiErrorCode;
import com.wms.shared.common.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private ISysUserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    /** SEC-03：登录/注册失败限速（2026-11-06） */
    @Autowired
    private LoginRateLimiter rateLimiter;

    /**
     * NEW-02：可信代理白名单（逗号分隔，默认空）。
     *
     * <p>空值表示<b>不信任任何 {@code X-Forwarded-For}</b> —— 本项目安装脚本不部署 nginx，
     * 请求直连 8091，此时 XFF 完全由客户端控制，采信它等于把限速关掉。
     * 将来若前面加了代理，在这里填代理地址（支持 CIDR，如 {@code 10.0.0.0/8}）。
     */
    @Value("${app.security.trusted-proxies:}")
    private String trustedProxies;

    private volatile ClientIpResolver clientIpResolver;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 懒建解析器（配置值注入完成后再读，避免初始化顺序问题） */
    private ClientIpResolver clientIpResolver() {
        ClientIpResolver resolver = this.clientIpResolver;
        if (resolver == null) {
            synchronized (this) {
                if (this.clientIpResolver == null) {
                    this.clientIpResolver = new ClientIpResolver(trustedProxies);
                }
                resolver = this.clientIpResolver;
            }
        }
        return resolver;
    }

    /**
     * 登录
     */
    @PostMapping("/login")
    public ResultVO<Map<String, Object>> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        // 入参防御（2026-10-31 服务器实测发现）：
        //  1. password 为 null 时，BCryptPasswordEncoder.matches(null, hash) 会抛
        //     IllegalArgumentException("rawPassword cannot be null") → 被 Spring 兜成
        //     **HTTP 500 + 默认错误页**，既不像业务失败、也会在日志里刷整段堆栈。
        //     实测：{"username":"admin"}（无 password 字段）→ 500。
        //     现在提前拦下来，返回与"口令错误"一致的提示。
        //  2. isDisabled 是 Integer，库中若为 NULL，`== 1` 的自动拆箱会 NPE。
        String username = (request == null) ? null : trimToNull(request.getUsername());
        String password = (request == null) ? null : request.getPassword();
        if (username == null || password == null || password.isEmpty()) {
            // QUAL-02：凭据类错误码 → HTTP 401（改造前是 HTTP 200 + code 500，网关/APM 看不见）
            return ResultVO.of(ApiErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        // SEC-03 限速：用户名+IP 维度，5 次失败/分钟 → 锁 5 分钟（防爆破与 BCrypt CPU DoS）
        String limitKey = "login|" + username.toLowerCase() + "|" + clientIp(httpRequest);
        if (rateLimiter.isBlocked(limitKey)) {
            long min = (rateLimiter.remainingLockMs(limitKey) + 59_999) / 60_000;
            return ResultVO.of(ApiErrorCode.AUTH_RATE_LIMITED,
                    "失败次数过多，账号已临时锁定，请约 " + min + " 分钟后再试");
        }

        SysUser user = userService.findByUsername(username);
        if (user == null || (user.getIsDisabled() != null && user.getIsDisabled() == 1)) {
            // 不存在的用户也计入失败：不给爆破者区分“用户不存在/口令错误”的侧信道
            rateLimiter.recordFailure(limitKey);
            return ResultVO.of(ApiErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            rateLimiter.recordFailure(limitKey);
            return ResultVO.of(ApiErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        rateLimiter.recordSuccess(limitKey);

        // 更新最后登录时间
        userService.updateLastLoginAt(user.getUserId());

        // 生成Token
        String token = jwtUtil.generateToken(user.getUsername(), user.getUserId(), user.getRole());

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("expiresIn", 86400);
        data.put("userId", user.getUserId());
        data.put("username", user.getUsername());
        data.put("realName", user.getRealName());
        data.put("role", user.getRole());
        data.put("factoryId", user.getFactoryId());

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userId", user.getUserId());
        userInfo.put("username", user.getUsername());
        userInfo.put("realName", user.getRealName());
        userInfo.put("role", user.getRole());
        userInfo.put("factoryId", user.getFactoryId());
        data.put("user", userInfo);

        return ResultVO.success(data);
    }

    /**
     * 注册（仅管理员可调用，或开放注册）
     */
    @PostMapping("/register")
    public ResultVO<String> register(@RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        // === 安全修复（2026-10-31 服务器实测发现的严重漏洞）===
        //
        // 原实现直接采用请求传入的 role：
        //     user.setRole(request.getRole() != null ? request.getRole() : 0);
        // 而 /api/auth/register 在 SecurityConfig 白名单里（匿名可调），
        // 实测：匿名 POST {"username":"evil","password":"...","role":1}
        //       → HTTP 200「注册成功」→ 库里真的写入了 role=1（管理员）
        //       → 用该账号登录拿到 token → /api/user、/api/v1/system/settings、
        //         /api/logs/operation 全部 200。
        // 也就是说：**无需任何凭据即可获得管理员权限**。
        //
        // 修复：**角色由服务端决定，永远为普通用户（0）**，
        // 请求里传什么都不采信。管理员账号只能由已登录的管理员在系统设置里创建。
        String username = (request == null) ? null : trimToNull(request.getUsername());
        String password = (request == null) ? null : request.getPassword();
        if (username == null || password == null || password.isEmpty()) {
            return ResultVO.of(ApiErrorCode.PARAM_MISSING, "用户名或密码不能为空");
        }

        // SEC-03 注册限速：按 IP 维度（弱口令批量注册成本抬高；配合下面的强度校验）
        String regKey = "reg|" + clientIp(httpRequest);
        if (rateLimiter.isBlocked(regKey)) {
            long min = (rateLimiter.remainingLockMs(regKey) + 59_999) / 60_000;
            return ResultVO.of(ApiErrorCode.AUTH_RATE_LIMITED,
                    "注册尝试过于频繁，请约 " + min + " 分钟后再试");
        }

        // SEC-03 口令最小强度：≥8 位 + 至少两类字符（QUAL-NEW-03）
        String strengthError = passwordStrengthError(password);
        if (strengthError != null) {
            rateLimiter.recordFailure(regKey);
            return ResultVO.of(ApiErrorCode.PARAM_WEAK_PASSWORD, strengthError);
        }

        // 检查用户名是否已存在
        if (userService.findByUsername(username) != null) {
            return ResultVO.of(ApiErrorCode.CONFLICT_USERNAME_EXISTS);
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRealName(request.getRealName());
        // 角色固定为普通用户，不接受请求传入的角色
        user.setRole(0);
        user.setFactoryId(request.getFactoryId());
        user.setIsDisabled(0);

        userService.save(user);
        rateLimiter.recordSuccess(regKey);
        return ResultVO.success("注册成功");
    }

    /**
     * SEC-03/QUAL-NEW-03：最小口令强度 —— 长度 ≥8 且至少包含字母/数字两类。
     * 现场系统不强制符号（避免产线工口令难记反贴便签），够挡住纯弱口令字典。
     *
     * @return null 表示通过；否则返回给前端的错误文案
     */
    private static String passwordStrengthError(String password) {
        if (password.length() < 8) {
            return "密码长度至少 8 位";
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            return "密码需同时包含字母和数字";
        }
        return null;
    }

    /**
     * 取用于限速键的客户端 IP。
     *
     * <p>NEW-02（2026-11-06 v5 评审）：原实现「有 X-Forwarded-For 就取首段」，
     * 但本系统是直连 8091（安装脚本不部署 nginx），XFF 完全由客户端伪造 ——
     * 换头即换限速桶。现改为：默认只用 socket 对端地址，
     * 只有对端命中 {@code app.security.trusted-proxies} 才采信 XFF。
     * 判定细节见 {@link ClientIpResolver}。
     */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return clientIpResolver().resolve(request.getRemoteAddr(), xff);
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/me")
    public ResultVO<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResultVO.of(ApiErrorCode.AUTH_TOKEN_INVALID, "缺少认证令牌");
        }
        String token = authHeader.replace("Bearer ", "");
        if (!jwtUtil.validateToken(token)) {
            return ResultVO.of(ApiErrorCode.AUTH_TOKEN_INVALID, "Token无效或已过期");
        }
        Long userId = jwtUtil.getUserIdFromToken(token);
        SysUser user = userService.getById(userId);

        if (user == null) {
            return ResultVO.of(ApiErrorCode.AUTH_TOKEN_INVALID, "用户不存在");
        }
        // SEC-03：/api/auth/me 在白名单内自行校验 token，必须同步检查禁用状态，
        // 否则禁用账号的存量 token 仍可通过本端点读到自己（受保护端点已 401）。
        if (user.getIsDisabled() != null && user.getIsDisabled() == 1) {
            return ResultVO.of(ApiErrorCode.AUTH_ACCOUNT_DISABLED);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getUserId());
        data.put("username", user.getUsername());
        data.put("realName", user.getRealName());
        data.put("role", user.getRole());
        data.put("factoryId", user.getFactoryId());

        return ResultVO.success(data);
    }

    // 内部类：登录请求
    public static class LoginRequest {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    // 内部类：注册请求
    public static class RegisterRequest {
        private String username;
        private String password;
        private String realName;
        private Integer role;
        private Long factoryId;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getRealName() { return realName; }
        public void setRealName(String realName) { this.realName = realName; }
        public Integer getRole() { return role; }
        public void setRole(Integer role) { this.role = role; }
        public Long getFactoryId() { return factoryId; }
        public void setFactoryId(Long factoryId) { this.factoryId = factoryId; }
    }

    /** 去掉首尾空白；null 或空白串一律返回 null（用于登录入参校验） */
    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
