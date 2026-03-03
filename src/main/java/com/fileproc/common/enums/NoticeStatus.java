package com.fileproc.common.enums;

/**
 * 通知状态枚举
 */
public enum NoticeStatus {

    /** 草稿 */
    DRAFT("draft"),
    /** 已发布 */
    PUBLISHED("published");

    private final String code;

    NoticeStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
