package com.fileproc.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 客户端 IP 工具类
 * 统一处理 X-Forwarded-For / X-Real-IP / RemoteAddr，取第一个有效 IP（防伪造）
 */
public final class IpUtil {

    private IpUtil() {}

    /**
     * 从当前 RequestContext 中获取客户端真实 IP
     */
    public static String getClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return "";
            return getClientIp(attrs.getRequest());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从指定 HttpServletRequest 中获取客户端真实 IP
     */
    public static String getClientIp(HttpServletRequest request) {
        try {
            String ip = request.getHeader("X-Forwarded-For");
            if (isBlankOrUnknown(ip)) {
                ip = request.getHeader("X-Real-IP");
            }
            if (isBlankOrUnknown(ip)) {
                ip = request.getRemoteAddr();
            }
            // X-Forwarded-For 可能是逗号分隔的 IP 链，取第一个（最原始客户端 IP）
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }
            return ip != null ? ip : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isBlankOrUnknown(String ip) {
        return ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip);
    }
}
