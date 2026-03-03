package com.fileproc.tenant.controller;

import com.fileproc.common.PageResult;
import com.fileproc.common.R;
import com.fileproc.tenant.entity.SysTenant;
import com.fileproc.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 超管租户管理接口
 */
@Tag(name = "超管-租户管理")
@Validated
@RestController
@RequestMapping("/admin/tenants")
@RequiredArgsConstructor
public class AdminTenantController {

    private final TenantService tenantService;

    @Operation(summary = "分页查询租户列表")
    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<PageResult<SysTenant>> list(
            @RequestParam(defaultValue = "1") int page,
            @Max(value = 100, message = "每页最多100条") @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword) {
        return R.ok(tenantService.pageList(page, pageSize, keyword));
    }

    @Operation(summary = "新建租户")
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<SysTenant> create(@Valid @RequestBody SysTenant tenant) {
        return R.ok(tenantService.createTenant(tenant));
    }

    @Operation(summary = "更新租户")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<SysTenant> update(@PathVariable String id, @Valid @RequestBody SysTenant tenant) {
        return R.ok(tenantService.updateTenant(id, tenant));
    }

    @Operation(summary = "删除租户")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<Void> delete(@PathVariable String id) {
        tenantService.deleteTenant(id);
        return R.ok();
    }

    @Operation(summary = "切换租户状态")
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<SysTenant> toggleStatus(@PathVariable String id,
                                     @Valid @RequestBody ToggleStatusReq req) {
        return R.ok(tenantService.toggleStatus(id, req.getStatus()));
    }

    @Data
    static class ToggleStatusReq {
        @NotBlank(message = "status不能为空")
        @Pattern(regexp = "^(active|disabled)$", message = "status只能为 active 或 disabled")
        private String status;
    }
}
