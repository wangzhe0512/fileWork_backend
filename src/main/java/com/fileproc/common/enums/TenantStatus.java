package com.fileproc.common.enums;

/**
 * 租户状态枚举
 */
public enum TenantStatus {

    /** 活跃 */
    ACTIVE("active"),
    /** 已禁用 */
    DISABLED("disabled");

    private final String code;

    TenantStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
