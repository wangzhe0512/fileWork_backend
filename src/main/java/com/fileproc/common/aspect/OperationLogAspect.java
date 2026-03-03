package com.fileproc.common.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileproc.common.TenantContext;
import com.fileproc.common.annotation.OperationLog;
import com.fileproc.common.util.IpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 操作日志 AOP 切面：异步写入 sys_log 表
 * 过滤敏感字段：password、token、secret
 * P1-11修复：通过 AsyncLogService 代理异步写入，解决 @Async 在 protected 方法上不生效的问题
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private final AsyncLogService asyncLogService;
    private final ObjectMapper objectMapper;

    /** 敏感字段关键词（出现则不记录该参数） */
    private static final List<String> SENSITIVE_KEYWORDS = Arrays.asList("password", "token", "secret", "pwd");

    @AfterReturning(pointcut = "@annotation(opLog)", returning = "result")
    public void afterReturning(JoinPoint joinPoint, OperationLog opLog, Object result) {
        try {
            // 在主线程采集 ThreadLocal / SecurityContext 数据，再传入异步方法
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String userId = "";
            String userName = "未知";
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                userName = auth.getName();
                if (auth.getPrincipal() instanceof org.springframework.security.core.userdetails.UserDetails ud) {
                    userId = ud.getUsername();
                }
            }
            String tenantId = TenantContext.getTenantId() != null ? TenantContext.getTenantId() : "";
            String ip = IpUtil.getClientIp();
            String detail = buildDetail(joinPoint);

            // P1-11：通过独立 Bean 的代理方法调用，确保 @Async 真正异步生效
            asyncLogService.saveLog(opLog.module(), opLog.action(), userId, userName, tenantId, ip, detail);
        } catch (Exception e) {
            log.warn("操作日志记录失败: {}", e.getMessage());
        }
    }

    private String buildDetail(JoinPoint joinPoint) {
        try {
            Object[] args = joinPoint.getArgs();
            if (args == null || args.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) {
                if (arg == null) continue;
                String argStr = arg.toString().toLowerCase();
                boolean isSensitive = SENSITIVE_KEYWORDS.stream().anyMatch(argStr::contains);
                if (!isSensitive) {
                    try {
                        sb.append(objectMapper.writeValueAsString(arg)).append(" ");
                    } catch (Exception ignored) {
                        sb.append(arg).append(" ");
                    }
                }
            }
            String detail = sb.toString().trim();
            return detail.length() > 2000 ? detail.substring(0, 2000) : detail;
        } catch (Exception e) {
            return "";
        }
    }
}
