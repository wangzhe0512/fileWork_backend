package com.fileproc.system.controller;

import com.fileproc.common.R;
import com.fileproc.system.entity.SysPermission;
import com.fileproc.system.service.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限接口
 * GET /system/permissions - 获取完整权限树
 */
@Tag(name = "系统-权限管理")
@RestController
@RequestMapping("/system/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    @Operation(summary = "获取权限树")
    @PreAuthorize("hasAuthority('system:permission:list')")
    @GetMapping
    public R<List<SysPermission>> tree() {
        return R.ok(permissionService.getPermissionTree());
    }
}
