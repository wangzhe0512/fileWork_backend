package com.fileproc.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fileproc.auth.entity.SysAdmin;
import com.fileproc.auth.mapper.AdminMapper;
import com.fileproc.auth.util.JwtUtil;
import com.fileproc.auth.util.TokenConstants;
import com.fileproc.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 超管认证 Service
 */
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminMapper adminMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    private static final String TOKEN_BLACKLIST_PREFIX = TokenConstants.BLACKLIST_PREFIX;
    /** P2-01：超管登录失败次数 Redis 前缀 */
    private static final String ADMIN_LOGIN_FAIL_PREFIX = "login:fail:admin:";
    private static final int MAX_FAIL_COUNT = 5;
    private static final long LOCK_TTL_SECONDS = 300L;

    /**
     * 超管登录
     * @return Map: token + adminId + username
     */
    public Map<String, Object> login(String username, String password) {
        // P2-01：超管登录频率限制
        String failKey = ADMIN_LOGIN_FAIL_PREFIX + username;
        String failVal = redisTemplate.opsForValue().get(failKey);
        if (failVal != null && Integer.parseInt(failVal) >= MAX_FAIL_COUNT) {
            throw BizException.of(429, "登录失败次数过多，请 5 分钟后再试");
        }

        SysAdmin admin = adminMapper.selectOne(
                new LambdaQueryWrapper<SysAdmin>()
                        .eq(SysAdmin::getUsername, username)
        );
        if (admin == null) {
            incrementAdminFail(failKey);
            throw BizException.of(401, "账号或密码错误");
        }
        // P1修复：先验状态，再验密码
        if (admin.getStatus() != null && "disabled".equals(admin.getStatus())) {
            throw BizException.of(403, "超管账号已被禁用");
        }
        if (!passwordEncoder.matches(password, admin.getPassword())) {
            incrementAdminFail(failKey);
            throw BizException.of(401, "账号或密码错误");
        }

        // 登录成功，清除失败计数
        redisTemplate.delete(failKey);

        String token = jwtUtil.generateAdminToken(admin.getId());
        return Map.of(
                "token", token,
                "adminId", admin.getId(),
                "username", admin.getUsername(),
                "realName", admin.getRealName() != null ? admin.getRealName() : ""
        );
    }

    private void incrementAdminFail(String key) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, LOCK_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /**
     * 超管登出（加入 Token 黑名单）
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
}
