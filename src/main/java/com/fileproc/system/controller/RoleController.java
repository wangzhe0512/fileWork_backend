package com.fileproc.system.controller;

import com.fileproc.common.R;
import com.fileproc.system.entity.SysRole;
import com.fileproc.system.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 角色管理接口
 * GET    /system/roles
 * POST   /system/roles
 * DELETE /system/roles/{id}
 * GET    /system/roles/{id}/permissions
 * PUT    /system/roles/{id}/permissions
 */
@Tag(name = "系统-角色管理")
@RestController
@RequestMapping("/system/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @Operation(summary = "获取角色列表")
    @PreAuthorize("hasAuthority('system:role:list')")
    @GetMapping
    public R<List<SysRole>> list() {
        return R.ok(roleService.listAll());
    }

    @Operation(summary = "新建角色")
    @PreAuthorize("hasAuthority('system:role:create')")
    @PostMapping
    public R<SysRole> create(@Valid @RequestBody SysRole role) {
        return R.ok(roleService.createRole(role));
    }

    @Operation(summary = "删除角色")
    @PreAuthorize("hasAuthority('system:role:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        roleService.deleteRole(id);
        return R.ok();
    }

    @Operation(summary = "获取角色权限列表")
    @PreAuthorize("hasAuthority('system:role:list')")
    @GetMapping("/{id}/permissions")
    public R<Map<String, Object>> getPermissions(@PathVariable String id) {
        return R.ok(roleService.getRolePermissions(id));
    }

    @Operation(summary = "更新角色权限")
    @PreAuthorize("hasAuthority('system:role:edit')")
    @PutMapping("/{id}/permissions")
    public R<Void> updatePermissions(@PathVariable String id,
                                     @RequestBody UpdatePermissionsRequest req) {
        roleService.updateRolePermissions(id, req.getPermissions());
        return R.ok();
    }

    @Data
    static class UpdatePermissionsRequest {
        private List<String> permissions;
    }
}
