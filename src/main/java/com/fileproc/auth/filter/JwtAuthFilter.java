package com.fileproc.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileproc.auth.util.JwtUtil;
import com.fileproc.common.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 普通用户 JWT 过滤器
 * 解析 Bearer Token → 校验签名 → 查 Redis 黑名单 → 设置 SecurityContext + TenantContext
 */
@Slf4j
public class JwtAuthFilter extends AbstractJwtAuthFilter {

    public JwtAuthFilter(JwtUtil jwtUtil, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        super(jwtUtil, redisTemplate, objectMapper);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            if (!jwtUtil.validateUserToken(token)) {
                writeUnauthorized(response, "Token 无效或已过期");
                return;
            }

            Claims claims = jwtUtil.parseUserToken(token);
            String jti = claims.getId();

            // 检查黑名单
            if (Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + jti))) {
                writeUnauthorized(response, "Token 已失效，请重新登录");
                return;
            }

            String userId = claims.getSubject();
            String tenantId = claims.get("tenantId", String.class);
            String roleId = claims.get("roleId", String.class);
            String realName = claims.get("realName", String.class);
            Object permsObj = claims.get("permissions");

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            if (permsObj instanceof List<?> permsList) {
                permsList.forEach(p -> authorities.add(new SimpleGrantedAuthority(p.toString())));
            }
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));

            UserPrincipal principal = new UserPrincipal(userId, tenantId, roleId, realName, authorities);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(principal, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);

            TenantContext.setTenantId(tenantId);

            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.warn("JWT 过滤器异常: {}", e.getMessage());
            writeUnauthorized(response, "Token 解析失败");
        } finally {
            TenantContext.clear();
        }
    }
}
