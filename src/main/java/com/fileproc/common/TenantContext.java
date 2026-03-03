package com.fileproc.common;

/**
 * 多租户上下文，使用 ThreadLocal 存储当前请求的 tenantId
 * 由 JwtAuthFilter 在每次请求时设置，请求结束后清除
 */
public class TenantContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void setTenantId(String tenantId) {
        HOLDER.set(tenantId);
    }

    public static String getTenantId() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
