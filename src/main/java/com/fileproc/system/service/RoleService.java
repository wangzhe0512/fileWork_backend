package com.fileproc.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fileproc.common.BizException;
import com.fileproc.common.TenantContext;
import com.fileproc.common.annotation.OperationLog;
import com.fileproc.system.entity.SysPermission;
import com.fileproc.system.entity.SysRole;
import com.fileproc.system.entity.SysRolePermission;
import com.fileproc.system.mapper.PermissionMapper;
import com.fileproc.system.mapper.RoleMapper;
import com.fileproc.system.mapper.RolePermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 角色管理 Service
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;

    public List<SysRole> listAll() {
        String tenantId = TenantContext.getTenantId();
        List<SysRole> roles = roleMapper.selectList(
                new LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getTenantId, tenantId)
                        .orderByAsc(SysRole::getCreatedAt)
        );
        if (roles.isEmpty()) return roles;
        // P1：批量查询所有角色权限，消除 N+1 问题
        List<String> roleIds = roles.stream().map(SysRole::getId).collect(Collectors.toList());
        List<java.util.Map<String, String>> permRows = rolePermissionMapper.selectPermsByRoleIds(roleIds);
        Map<String, List<String>> permsMap = permRows.stream()
                .collect(Collectors.groupingBy(
                        row -> row.get("roleId"),
                        Collectors.mapping(row -> row.get("permissionCode"), Collectors.toList())
                ));
        roles.forEach(role -> role.setPermissions(permsMap.getOrDefault(role.getId(), List.of())));
        return roles;
    }

    @OperationLog(module = "角色管理", action = "新建角色")
    public SysRole createRole(SysRole role) {
        String tenantId = TenantContext.getTenantId();
        role.setId(UUID.randomUUID().toString());
        role.setTenantId(tenantId);
        role.setCreatedAt(LocalDateTime.now());
        roleMapper.insert(role);
        return role;
    }

    @OperationLog(module = "角色管理", action = "删除角色")
    @Transactional
    public void deleteRole(String id) {
        if (roleMapper.selectById(id) == null) throw BizException.notFound("角色");
        roleMapper.deleteById(id);
        rolePermissionMapper.deleteByRoleId(id);
    }

    /** 获取角色权限列表（含 checkedIds 用于前端树形勾选回显） */
    public Map<String, Object> getRolePermissions(String roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) throw BizException.notFound("角色");
        List<String> permissions = rolePermissionMapper.selectPermCodesByRoleId(roleId);
        List<String> checkedIds = rolePermissionMapper.selectPermIdsByRoleId(roleId);
        return Map.of("roleId", roleId, "permissions", permissions, "checkedIds", checkedIds);
    }

    /** 更新角色权限 */
    @OperationLog(module = "权限管理", action = "保存角色权限")
    @Transactional
    public void updateRolePermissions(String roleId, List<String> permissions) {
        if (roleMapper.selectById(roleId) == null) throw BizException.notFound("角色");
        // P1-SEC-08：校验权限码是否都存在于 sys_permission 表，防止写入无效权限
        if (permissions != null && !permissions.isEmpty()) {
            long validCount = permissionMapper.selectCount(
                    new LambdaQueryWrapper<SysPermission>().in(SysPermission::getCode, permissions));
            if (validCount != permissions.size()) {
                throw BizException.of(400, "包含无效的权限码，请刷新权限树后重试");
            }
        }
        // 先删再批量插
        rolePermissionMapper.deleteByRoleId(roleId);
        if (permissions != null && !permissions.isEmpty()) {
            List<SysRolePermission> list = permissions.stream()
                    .map(code -> {
                        SysRolePermission rp = new SysRolePermission();
                        rp.setId(UUID.randomUUID().toString());
                        rp.setRoleId(roleId);
                        rp.setPermissionCode(code);
                        return rp;
                    })
                    .collect(Collectors.toList());
            rolePermissionMapper.batchInsert(list);
        }
    }
}
