package com.fileproc.auth.controller;

import com.fileproc.auth.service.AuthService;
import com.fileproc.auth.filter.UserPrincipal;
import com.fileproc.common.R;
import com.fileproc.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户认证接口
 * GET  /tenants/list        - 获取活跃租户列表（登录页选择租户）
 * POST /auth/login          - 用户登录
 * POST /auth/logout         - 用户登出
 * GET  /auth/userinfo       - 获取当前用户信息
 */
@Tag(name = "用户认证")
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final TenantService tenantService;

    @Operation(summary = "获取租户列表（登录页使用）")
    @GetMapping("/tenants/list")
    public R<Object> getTenantList() {
        return R.ok(tenantService.getActiveTenantList());
    }

    @Operation(summary = "用户登录")
    @PostMapping("/auth/login")
    public R<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(authService.login(req.getUsername(), req.getPassword(), req.getTenantId()));
    }

    @Operation(summary = "用户登出")
    @PostMapping("/auth/logout")
    public R<Void> logout(HttpServletRequest request) {
        String token = extractToken(request);
        authService.logout(token);
        return R.ok();
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/auth/userinfo")
    public R<Map<String, Object>> getUserInfo(@AuthenticationPrincipal UserPrincipal principal) {
        // P2-05：principal 判空保护
        if (principal == null) {
            throw com.fileproc.common.BizException.unauthorized("登录状态异常，请重新登录");
        }
        return R.ok(authService.getUserInfo(principal.getUserId()));
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        return null;
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
        @NotBlank(message = "租户ID不能为空")
        private String tenantId;
    }
}
