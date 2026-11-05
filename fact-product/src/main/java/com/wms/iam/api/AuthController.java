package com.wms.iam.api;

import com.wms.iam.domain.model.SysUser;
import com.wms.iam.app.ISysUserService;
import com.wms.iam.domain.JwtUtil;
import com.wms.shared.common.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

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

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 登录
     */
    @PostMapping("/login")
    public ResultVO<Map<String, Object>> login(@RequestBody LoginRequest request) {
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
            return ResultVO.error("用户名或密码错误");
        }

        SysUser user = userService.findByUsername(username);
        if (user == null || (user.getIsDisabled() != null && user.getIsDisabled() == 1)) {
            return ResultVO.error("用户不存在或已被禁用");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            return ResultVO.error("用户名或密码错误");
        }

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
    public ResultVO<String> register(@RequestBody RegisterRequest request) {
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
            return ResultVO.error("用户名或密码不能为空");
        }

        // 检查用户名是否已存在
        if (userService.findByUsername(username) != null) {
            return ResultVO.error("用户名已存在");
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
        return ResultVO.success("注册成功");
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/me")
    public ResultVO<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResultVO.error("缺少认证令牌");
        }
        String token = authHeader.replace("Bearer ", "");
        if (!jwtUtil.validateToken(token)) {
            return ResultVO.error("Token无效或已过期");
        }
        Long userId = jwtUtil.getUserIdFromToken(token);
        SysUser user = userService.getById(userId);

        if (user == null) {
            return ResultVO.error("用户不存在");
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
