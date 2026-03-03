package com.fileproc.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fileproc.auth.util.JwtUtil;
import com.fileproc.auth.util.TokenConstants;
import com.fileproc.common.BizException;
import com.fileproc.common.TenantContext;
import com.fileproc.common.util.IpUtil;
import com.fileproc.system.entity.SysUser;
import com.fileproc.system.mapper.RoleMapper;
import com.fileproc.system.mapper.RolePermissionMapper;
import com.fileproc.system.mapper.UserMapper;
import com.fileproc.tenant.entity.SysTenant;
import com.fileproc.tenant.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户认证 Service
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final TenantMapper tenantMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    private static final String TOKEN_BLACKLIST_PREFIX = TokenConstants.BLACKLIST_PREFIX;
    /** P2-01：登录失败次数 Redis 前缀 */
    private static final String LOGIN_FAIL_PREFIX = "login:fail:";
    private static final int MAX_FAIL_COUNT = 5;
    private static final long LOCK_TTL_SECONDS = 300L; // 5分钟

    /**
     * 用户登录
     */
    public Map<String, Object> login(String username, String password, String tenantId) {
        // P2-01：登录频率限制
        checkLoginRateLimit(username + ":" + tenantId);

        // 校验租户
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || "disabled".equals(tenant.getStatus())) {
            throw BizException.of(400, "租户不存在或已被禁用");
        }

        // 临时设置租户上下文（供租户插件使用）
        TenantContext.setTenantId(tenantId);
        try {
            SysUser user = userMapper.selectOne(
                    new LambdaQueryWrapper<SysUser>()
                            .eq(SysUser::getUsername, username)
                            .eq(SysUser::getTenantId, tenantId)
            );
            if (user == null) {
                incrementLoginFail(username + ":" + tenantId);
                throw BizException.of(401, "账号或密码错误");
            }
            // P1修复：先验状态，再验密码，防止账号枚举攻击
            if ("disabled".equals(user.getStatus())) {
                throw BizException.of(403, "账号已被禁用");
            }
            if (!passwordEncoder.matches(password, user.getPassword())) {
                incrementLoginFail(username + ":" + tenantId);
                throw BizException.of(401, "账号或密码错误");
            }

            // 登录成功，清除失败计数
            clearLoginFailCount(username + ":" + tenantId);

            // 获取角色权限
            List<String> permissions = List.of();
            if (user.getRoleId() != null) {
                permissions = rolePermissionMapper.selectPermCodesByRoleId(user.getRoleId());
            }

            String token = jwtUtil.generateUserToken(
                    user.getId(), tenantId, user.getRoleId(),
                    user.getRealName(),
                    permissions.toArray(new String[0])
            );

            // P1修复：更新最后登录时间和 IP
            String loginIp = IpUtil.getClientIp();
            userMapper.updateLastLogin(user.getId(), LocalDateTime.now(), loginIp);

            // 构建用户信息
            String roleName = "";
            if (user.getRoleId() != null) {
                var role = roleMapper.selectById(user.getRoleId());
                if (role != null) roleName = role.getName();
            }

            return Map.of(
                    "token", token,
                    "userInfo", buildUserInfo(user, roleName, permissions)
            );
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 用户登出（加入 Token 黑名单）
     */
    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        String jti = jwtUtil.getJtiFromToken(token);
        if (jti != null) {
            long ttl = jwtUtil.getRemainingTtl(token);
            if (ttl > 0) {
                redisTemplate.opsForValue().set(
                        TOKEN_BLACKLIST_PREFIX + jti, "1", ttl, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * 获取当前用户信息
     */
    public Map<String, Object> getUserInfo(String userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) throw BizException.notFound("用户");

        List<String> permissions = List.of();
        String roleName = "";
        if (user.getRoleId() != null) {
            permissions = rolePermissionMapper.selectPermCodesByRoleId(user.getRoleId());
            var role = roleMapper.selectById(user.getRoleId());
            if (role != null) roleName = role.getName();
        }
        return buildUserInfo(user, roleName, permissions);
    }

    private Map<String, Object> buildUserInfo(SysUser user, String roleName, List<String> permissions) {
        // P2修复：改用 HashMap 避免 Map.of() 不允许 null 值导致 NPE
        Map<String, Object> info = new java.util.HashMap<>();
        info.put("id", user.getId() != null ? user.getId() : "");
        info.put("username", user.getUsername());
        info.put("realName", user.getRealName() != null ? user.getRealName() : "");
        info.put("email", user.getEmail() != null ? user.getEmail() : "");
        info.put("phone", user.getPhone() != null ? user.getPhone() : "");
        info.put("roleId", user.getRoleId() != null ? user.getRoleId() : "");
        info.put("roleName", roleName);
        info.put("avatar", user.getAvatar() != null ? user.getAvatar() : "");
        info.put("tenantId", user.getTenantId() != null ? user.getTenantId() : "");
        info.put("permissions", permissions);
        return info;
    }

    /** P2-01：检查登录失败频率限制 */
    private void checkLoginRateLimit(String key) {
        String redisKey = LOGIN_FAIL_PREFIX + key;
        String val = redisTemplate.opsForValue().get(redisKey);
        if (val != null && Integer.parseInt(val) >= MAX_FAIL_COUNT) {
            throw BizException.of(429, "登录失败次数过多，请 5 分钟后再试");
        }
    }

    /** P2-01：增加登录失败次数 */
    private void incrementLoginFail(String key) {
        String redisKey = LOGIN_FAIL_PREFIX + key;
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(redisKey, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** P2-01：登录成功后清除失败计数 */
    private void clearLoginFailCount(String key) {
        redisTemplate.delete(LOGIN_FAIL_PREFIX + key);
    }
}
