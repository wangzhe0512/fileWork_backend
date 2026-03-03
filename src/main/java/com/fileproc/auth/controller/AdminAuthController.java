package com.fileproc.auth.controller;

import com.fileproc.auth.service.AdminAuthService;
import com.fileproc.common.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 超管认证接口
 * POST /admin/login   - 超管登录
 * POST /admin/logout  - 超管登出
 */
@Tag(name = "超管认证")
@RestController
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    @Operation(summary = "超管登录")
    @PostMapping("/admin/login")
    public R<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(adminAuthService.login(req.getUsername(), req.getPassword()));
    }

    @Operation(summary = "超管登出")
    @PostMapping("/admin/logout")
    public R<Void> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
        adminAuthService.logout(token);
        return R.ok();
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
    }
}
