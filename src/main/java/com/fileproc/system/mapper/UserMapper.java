package com.fileproc.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fileproc.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 用户 Mapper，自定义联表查询回显 roleName
 */
@Mapper
public interface UserMapper extends BaseMapper<SysUser> {

    @Select("""
        SELECT u.id, u.tenant_id, u.username, u.real_name, u.email, u.phone,
               u.role_id, u.status, u.created_at, u.updated_at,
               u.last_login_at, u.last_login_ip, u.deleted,
               r.name AS role_name
        FROM sys_user u
        LEFT JOIN sys_role r ON u.role_id = r.id AND r.deleted = 0
        WHERE u.deleted = 0
          AND u.tenant_id = #{tenantId}
          AND (#{keyword} IS NULL OR #{keyword} = ''
               OR u.username LIKE CONCAT('%', #{keyword}, '%')
               OR u.real_name LIKE CONCAT('%', #{keyword}, '%')
               OR u.email LIKE CONCAT('%', #{keyword}, '%'))
        ORDER BY u.created_at DESC
        """)
    IPage<SysUser> selectPageWithRole(Page<SysUser> page,
                                      @Param("tenantId") String tenantId,
                                      @Param("keyword") String keyword);

    /**
     * 登录成功后更新最后登录时间和 IP（P1 修复）
     */
    @Update("UPDATE sys_user SET last_login_at = #{loginAt}, last_login_ip = #{loginIp} WHERE id = #{userId}")
    void updateLastLogin(@Param("userId") String userId,
                         @Param("loginAt") LocalDateTime loginAt,
                         @Param("loginIp") String loginIp);
}
