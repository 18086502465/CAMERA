package com.wms.iam.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.iam.domain.model.SysUser;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统用户 Mapper
 *
 * <p><b>2026-10-31 服务器实测修正</b>：
 * 本接口原先同时存在 {@code @Select} 注解与 {@code mapper/SysUserMapper.xml} 里的
 * 同名 {@code <select>}，MyBatis-Plus 的规则是 **XML 优先于注解**，
 * 于是每次应用启动都会在日志里打两条 ERROR：
 *
 * <pre>
 * ERROR  mapper[com.wms.iam.infra.mapper.SysUserMapper.findByUsername] is ignored,
 *        because it exists, maybe from xml file
 * ERROR  mapper[com.wms.iam.infra.mapper.SysUserMapper.findByFactoryId] is ignored,
 *        because it exists, maybe from xml file
 * </pre>
 *
 * <p>功能上没坏（XML 版本生效），但每次重启都刷 ERROR 会掩盖真正的错误，
 * 排查时被噪声干扰。这里<b>删除注解，只保留 XML</b>。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 根据用户名查询用户
     * <p>SQL 定义在 {@code resources/mapper/SysUserMapper.xml}。</p>
     */
    SysUser findByUsername(@Param("username") String username);

    /**
     * 根据工厂ID查询用户列表
     * <p>SQL 定义在 {@code resources/mapper/SysUserMapper.xml}。</p>
     */
    List<SysUser> findByFactoryId(@Param("factoryId") Long factoryId);
}
