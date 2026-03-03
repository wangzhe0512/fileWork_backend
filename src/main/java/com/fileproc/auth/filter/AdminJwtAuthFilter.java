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
import java.util.List;

/**
 * 超管 JWT 过滤器，仅拦截 /admin/** 路径
 */
@Slf4j
public class AdminJwtAuthFilter extends AbstractJwtAuthFilter {

    public AdminJwtAuthFilter(JwtUtil jwtUtil, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
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
            if (!jwtUtil.validateAdminToken(token)) {
                writeUnauthorized(response, "超管 Token 无效或已过期");
                return;
            }

            Claims claims = jwtUtil.parseAdminToken(token);
            String jti = claims.getId();

            if (Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + jti))) {
                writeUnauthorized(response, "超管 Token 已失效，请重新登录");
                return;
            }

            String adminId = claims.getSubject();
            List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")
            );

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(adminId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);

            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.warn("超管 JWT 过滤器异常: {}", e.getMessage());
            writeUnauthorized(response, "超管 Token 解析失败");
        } finally {
            TenantContext.clear();
        }
    }
}
