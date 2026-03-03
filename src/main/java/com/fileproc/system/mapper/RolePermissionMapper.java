package com.fileproc.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.system.entity.SysRolePermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RolePermissionMapper extends BaseMapper<SysRolePermission> {

    @Select("SELECT permission_code FROM sys_role_permission WHERE role_id = #{roleId}")
    List<String> selectPermCodesByRoleId(@Param("roleId") String roleId);

    /**
     * 批量查询多个角色的权限 code（消除 N+1 问题）
     */
    @Select("<script>" +
            "SELECT role_id, permission_code FROM sys_role_permission WHERE role_id IN " +
            "<foreach collection='roleIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    List<java.util.Map<String, String>> selectPermsByRoleIds(@Param("roleIds") List<String> roleIds);

    /**
     * 查询角色绑定的权限 ID 列表（用于前端权限树勾选回显）
     */
    @Select("SELECT p.id FROM sys_role_permission rp " +
            "JOIN sys_permission p ON rp.permission_code = p.code " +
            "WHERE rp.role_id = #{roleId}")
    List<String> selectPermIdsByRoleId(@Param("roleId") String roleId);

    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    void deleteByRoleId(@Param("roleId") String roleId);

    /**
     * 批量插入角色权限关系
     */
    @Insert("<script>" +
            "INSERT INTO sys_role_permission (id, role_id, permission_code) VALUES " +
            "<foreach collection='list' item='rp' separator=','>" +
            "(#{rp.id}, #{rp.roleId}, #{rp.permissionCode})" +
            "</foreach>" +
            "</script>")
    void batchInsert(@Param("list") List<SysRolePermission> list);
}
