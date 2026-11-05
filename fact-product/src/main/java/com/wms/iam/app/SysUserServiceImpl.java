package com.wms.iam.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.iam.domain.model.SysUser;
import com.wms.iam.infra.mapper.SysUserMapper;
import com.wms.iam.app.ISysUserService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 系统用户 Service 实现类
 */
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements ISysUserService {

    @Autowired
    private SysUserMapper sysUserMapper;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public SysUser findByUsername(String username) {
        return sysUserMapper.findByUsername(username);
    }

    @Override
    public List<SysUser> findByFactoryId(Long factoryId) {
        return sysUserMapper.findByFactoryId(factoryId);
    }

    @Override
    public void updateLastLoginAt(Long userId) {
        SysUser user = getById(userId);
        if (user != null) {
            user.setLastLoginAt(new Date());
            updateById(user);
        }
    }

    @Override
    public SysUser login(String username, String password) {
        SysUser user = findByUsername(username);
        if (user == null || user.getIsDisabled() == 1) {
            return null;
        }
        if (passwordEncoder.matches(password, user.getPasswordHash())) {
            return user;
        }
        return null;
    }

    @Override
    public boolean saveUser(SysUser user) {
        // 密码加密
        if (user.getPasswordHash() != null) {
            user.setPasswordHash(passwordEncoder.encode(user.getPasswordHash()));
        }
        return save(user);
    }

    @Override
    public boolean updateUser(SysUser user) {
        // 如果传入了新密码，则加密
        if (user.getPasswordHash() != null && !user.getPasswordHash().startsWith("$2a$")) {
            user.setPasswordHash(passwordEncoder.encode(user.getPasswordHash()));
        }
        return updateById(user);
    }

    @Override
    public boolean deleteUser(Long userId) {
        SysUser user = getById(userId);
        if (user != null) {
            user.setIsDisabled(1);
            return updateById(user);
        }
        return false;
    }

    @Override
    public com.baomidou.mybatisplus.core.metadata.IPage<SysUser> pageQuery(
            Integer page, Integer size, String keyword, Integer role, Long factoryId) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysUser> pageParam = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(keyword)) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or()
                    .like(SysUser::getRealName, keyword));
        }
        if (role != null) {
            wrapper.eq(SysUser::getRole, role);
        }
        if (factoryId != null) {
            wrapper.eq(SysUser::getFactoryId, factoryId);
        }
        wrapper.orderByDesc(SysUser::getCreateTime);
        return page(pageParam, wrapper);
    }
}
