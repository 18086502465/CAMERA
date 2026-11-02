package com.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wms.entity.SysUser;

import java.util.List;

/**
 * 系统用户 Service 接口
 */
public interface ISysUserService extends IService<SysUser> {

    /**
     * 根据用户名查询用户
     */
    SysUser findByUsername(String username);

    /**
     * 根据工厂ID查询用户列表
     */
    List<SysUser> findByFactoryId(Long factoryId);

    /**
     * 更新用户最后登录时间
     */
    void updateLastLoginAt(Long userId);

    /**
     * 用户登录验证
     */
    SysUser login(String username, String password);

    /**
     * 保存用户（密码加密）
     */
    boolean saveUser(SysUser user);

    /**
     * 更新用户（密码加密）
     */
    boolean updateUser(SysUser user);

    /**
     * 删除用户（逻辑删除）
     */
    boolean deleteUser(Long userId);

    /**
     * 分页查询用户列表
     */
    com.baomidou.mybatisplus.core.metadata.IPage<SysUser> pageQuery(
            Integer page, Integer size, String keyword, Integer role, Long factoryId);
}
