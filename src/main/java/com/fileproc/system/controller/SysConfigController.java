package com.fileproc.system.controller;

import com.fileproc.common.R;
import com.fileproc.system.entity.SysConfig;
import com.fileproc.system.service.SysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 系统配置接口
 * GET /system/config
 * PUT /system/config
 */
@Tag(name = "系统-配置")
@RestController
@RequestMapping("/system/config")
@RequiredArgsConstructor
public class SysConfigController {

    private final SysConfigService sysConfigService;

    @Operation(summary = "获取系统配置")
    @PreAuthorize("hasAuthority('system:config:view')")
    @GetMapping
    public R<SysConfig> get() {
        return R.ok(sysConfigService.getConfig());
    }

    @Operation(summary = "更新系统配置")
    @PreAuthorize("hasAuthority('system:config:edit')")
    @PutMapping
    public R<SysConfig> update(@Valid @RequestBody SysConfig config) {
        return R.ok(sysConfigService.updateConfig(config));
    }
}
