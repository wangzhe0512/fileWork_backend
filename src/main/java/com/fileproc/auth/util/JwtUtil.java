package com.fileproc.auth.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具类：双密钥（用户 Token / 超管 Token）
 * P2：@PostConstruct 缓存 SecretKey，避免每次调用重新计算
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.user.secret}")
    private String userSecret;

    @Value("${jwt.user.expire:86400}")
    private long userExpire;

    @Value("${jwt.admin.secret}")
    private String adminSecret;

    @Value("${jwt.admin.expire:28800}")
    private long adminExpire;

    /** P2：缓存 SecretKey，避免每次 IO 调用重新计算 */
    private SecretKey cachedUserKey;
    private SecretKey cachedAdminKey;

    @PostConstruct
    public void init() {
        // P2-02：校验密钥长度，不足 32 字符（256位）时拒绝启动
        if (userSecret == null || userSecret.length() < 32) {
            throw new IllegalStateException("jwt.user.secret 长度不足 32 字符，存在安全风险，请修改配置");
        }
        if (adminSecret == null || adminSecret.length() < 32) {
            throw new IllegalStateException("jwt.admin.secret 长度不足 32 字符，存在安全风险，请修改配置");
        }
        cachedUserKey = Keys.hmacShaKeyFor(userSecret.getBytes(StandardCharsets.UTF_8));
        cachedAdminKey = Keys.hmacShaKeyFor(adminSecret.getBytes(StandardCharsets.UTF_8));
    }

    // ===================== 用户 Token =====================

    /**
     * 生成用户 Token
     * claims: userId, tenantId, roleId, permissions, realName
     */
    public String generateUserToken(String userId, String tenantId, String roleId, String realName, String[] permissions) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId)
                .claim("tenantId", tenantId)
                .claim("roleId", roleId)
                .claim("permissions", permissions)
                .claim("realName", realName != null ? realName : "")
                .claim("type", "user")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + userExpire * 1000L))
                .signWith(cachedUserKey)
                .compact();
    }

    /** 解析用户 Token Claims */
    public Claims parseUserToken(String token) {
        return Jwts.parser()
                .verifyWith(cachedUserKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 校验用户 Token 合法性（P2：区分过期与签名错误） */
    public boolean validateUserToken(String token) {
        try {
            parseUserToken(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("用户Token已过期: {}", e.getMessage());
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("用户Token校验失败: {}", e.getMessage());
            return false;
        }
    }

    // ===================== 超管 Token =====================

    /**
     * 生成超管 Token
     */
    public String generateAdminToken(String adminId) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(adminId)
                .claim("role", "SUPER_ADMIN")
                .claim("type", "admin")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + adminExpire * 1000L))
                .signWith(cachedAdminKey)
                .compact();
    }

    /** 解析超管 Token Claims */
    public Claims parseAdminToken(String token) {
        return Jwts.parser()
                .verifyWith(cachedAdminKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 校验超管 Token 合法性（P2：区分过期与签名错误） */
    public boolean validateAdminToken(String token) {
        try {
            parseAdminToken(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("超管Token已过期: {}", e.getMessage());
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("超管Token校验失败: {}", e.getMessage());
            return false;
        }
    }

    // ===================== 通用工具 =====================

    /**
     * 从 Token 中获取 jti（用于黑名单）
     * 先尝试用户密钥，再尝试超管密钥
     */
    public String getJtiFromToken(String token) {
        try {
            return parseUserToken(token).getId();
        } catch (Exception e) {
            try {
                return parseAdminToken(token).getId();
            } catch (Exception ex) {
                return null;
            }
        }
    }

    /**
     * 获取 Token 剩余有效期（毫秒），用于设置 Redis TTL
     */
    public long getRemainingTtl(String token) {
        try {
            Date expiration;
            try {
                expiration = parseUserToken(token).getExpiration();
            } catch (Exception e) {
                expiration = parseAdminToken(token).getExpiration();
            }
            long remaining = expiration.getTime() - System.currentTimeMillis();
            return Math.max(remaining, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private SecretKey getUserKey() { return cachedUserKey; }
    private SecretKey getAdminKey() { return cachedAdminKey; }
}
