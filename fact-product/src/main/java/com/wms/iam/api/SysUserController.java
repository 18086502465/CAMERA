package com.wms.iam.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wms.iam.domain.model.SysUser;
import com.wms.iam.app.ISysUserService;
import com.wms.iam.domain.JwtUtil;
import com.wms.shared.common.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统用户管理 API
 */
@RestController
@RequestMapping("/api/user")
public class SysUserController {

    @Autowired
    private ISysUserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 分页查询用户列表
     */
    @GetMapping
    public ResultVO<IPage<SysUser>> pageQuery(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer role,
            @RequestParam(required = false) Long factoryId) {
        return ResultVO.success(userService.pageQuery(page, size, keyword, role, factoryId));
    }

    /**
     * 获取用户详情
     */
    @GetMapping("/{id}")
    public ResultVO<SysUser> getById(@PathVariable Long id) {
        return ResultVO.success(userService.getById(id));
    }

    /**
     * 创建用户（管理员）
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResultVO<String> create(@RequestBody SysUser user) {
        if (userService.findByUsername(user.getUsername()) != null) {
            return ResultVO.error("用户名已存在");
        }
        user.setPasswordHash(passwordEncoder.encode(user.getPasswordHash()));
        userService.save(user);
        return ResultVO.success("创建成功");
    }

    /**
     * 更新用户
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResultVO<String> update(@PathVariable Long id, @RequestBody SysUser user) {
        user.setUserId(id);
        SysUser existing = userService.getById(id);
        if (existing == null) {
            return ResultVO.error("用户不存在");
        }
        // 如果传入了新密码，则加密
        if (user.getPasswordHash() != null && !user.getPasswordHash().trim().isEmpty() && !user.getPasswordHash().startsWith("$2a$")) {
            user.setPasswordHash(passwordEncoder.encode(user.getPasswordHash()));
        } else {
            // 不更新密码
            user.setPasswordHash(existing.getPasswordHash());
        }
        userService.updateById(user);
        return ResultVO.success("更新成功");
    }

    /**
     * 删除用户（逻辑删除）
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResultVO<String> delete(@PathVariable Long id) {
        userService.removeById(id);
        return ResultVO.success("删除成功");
    }

    /**
     * 禁用/启用用户
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}/toggle-status")
    public ResultVO<String> toggleStatus(@PathVariable Long id) {
        SysUser user = userService.getById(id);
        if (user != null) {
            user.setIsDisabled(user.getIsDisabled() == 1 ? 0 : 1);
            userService.updateById(user);
            return ResultVO.success(user.getIsDisabled() == 1 ? "已禁用" : "已启用");
        }
        return ResultVO.error("用户不存在");
    }

    /**
     * 登录
     */
    @PostMapping("/login")
    public ResultVO<Map<String, Object>> login(@RequestBody LoginDTO loginDTO) {
        SysUser user = userService.findByUsername(loginDTO.getUsername());
        if (user == null || user.getIsDisabled() == 1) {
            return ResultVO.error("用户不存在或已被禁用");
        }

        if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPasswordHash())) {
            return ResultVO.error("用户名或密码错误");
        }

        // 更新最后登录时间
        user.setLastLoginAt(new Date());
        userService.updateById(user);

        // 生成Token
        String token = jwtUtil.generateToken(user.getUsername(), user.getUserId(), user.getRole());

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", user.getUserId());
        data.put("username", user.getUsername());
        data.put("realName", user.getRealName());
        data.put("role", user.getRole());
        data.put("factoryId", user.getFactoryId());

        return ResultVO.success(data);
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

    /**
     * 登录DTO
     */
    public static class LoginDTO {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
