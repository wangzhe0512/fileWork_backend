package com.fileproc.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileproc.auth.util.JwtUtil;
import com.fileproc.common.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JWT 过滤器公共父类，消除 JwtAuthFilter 与 AdminJwtAuthFilter 的重复代码
 */
@RequiredArgsConstructor
public abstract class AbstractJwtAuthFilter extends OncePerRequestFilter {

    protected final JwtUtil jwtUtil;
    protected final StringRedisTemplate redisTemplate;
    protected final ObjectMapper objectMapper;

    protected static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";

    /**
     * 从 Authorization 头中提取 Bearer Token
     */
    protected String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        return null;
    }

    /**
     * 写出 401 响应
     */
    protected void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.unauthorized(message)));
    }
}
