package com.fileproc.system.controller;

import com.fileproc.common.PageResult;
import com.fileproc.common.R;
import com.fileproc.system.entity.SysUser;
import com.fileproc.system.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 用户管理接口
 * GET    /system/users
 * POST   /system/users
 * PUT    /system/users/{id}
 * DELETE /system/users/{id}
 */
@Tag(name = "系统-用户管理")
@Validated
@RestController
@RequestMapping("/system/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "分页查询用户")
    @GetMapping
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<PageResult<SysUser>> list(
            @RequestParam(defaultValue = "1") int page,
            @Max(value = 100, message = "每页最多100条") @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword) {
        return R.ok(userService.pageList(page, pageSize, keyword));
    }

    @Operation(summary = "新建用户")
    @PostMapping
    @PreAuthorize("hasAuthority('system:user:create')")
    public R<SysUser> create(@Valid @RequestBody SysUser user) {
        return R.ok(userService.createUser(user));
    }

    @Operation(summary = "编辑用户")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:edit')")
    public R<SysUser> update(@PathVariable String id, @Valid @RequestBody SysUser user) {
        return R.ok(userService.updateUser(id, user));
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:delete')")
    public R<Void> delete(@PathVariable String id) {
        userService.deleteUser(id);
        return R.ok();
    }

    @Operation(summary = "修改当前用户密码")
    @PostMapping("/change-password")
    @PreAuthorize("hasAuthority('system:user:edit')")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        userService.changePassword(req.getOldPassword(), req.getNewPassword());
        return R.ok();
    }

    @lombok.Data
    public static class ChangePasswordRequest {
        @jakarta.validation.constraints.NotBlank(message = "当前密码不能为空")
        private String oldPassword;
        @jakarta.validation.constraints.NotBlank(message = "新密码不能为空")
        @jakarta.validation.constraints.Size(min = 6, message = "新密码至少6位")
        private String newPassword;
    }
}
