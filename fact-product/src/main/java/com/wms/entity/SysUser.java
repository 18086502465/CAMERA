package com.wms.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;

/**
 * 系统用户表
 */
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long userId;

    private String username;      // 登录用户名（唯一）
    private String passwordHash;   // BCrypt加密密码
    private String realName;       // 真实姓名
    private Integer role;          // 角色：0-工人，1-管理员
    private Long factoryId;        // 所属工厂ID（工人限定范围）
    private String avatarUrl;     // 头像URL
    private Integer isDisabled;    // 0-正常，1-禁用
    private Date lastLoginAt;     // 最后登录时间
    private Date createTime;
    private Date updateTime;

    // 非数据库字段：确认密码（用于注册）
    @TableField(exist = false)
    private String confirmPassword;

    // Getters and Setters
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = realName; }

    public Integer getRole() { return role; }
    public void setRole(Integer role) { this.role = role; }

    public Long getFactoryId() { return factoryId; }
    public void setFactoryId(Long factoryId) { this.factoryId = factoryId; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public Integer getIsDisabled() { return isDisabled; }
    public void setIsDisabled(Integer isDisabled) { this.isDisabled = isDisabled; }

    public Date getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Date lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }

    public String getConfirmPassword() { return confirmPassword; }
    public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
}
