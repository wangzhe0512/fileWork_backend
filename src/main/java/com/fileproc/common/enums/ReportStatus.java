package com.fileproc.common.enums;

/**
 * 报告状态枚举
 * status 字段：editing（编辑中）、history（已归档）
 * generationStatus 字段：pending（待生成）、processing（生成中）、success（成功）、failed（失败）
 */
public enum ReportStatus {

    /** 编辑中 */
    EDITING("editing"),
    /** 已归档 */
    HISTORY("history"),
    /** 待生成 */
    PENDING("pending"),
    /** 生成中（异步执行中） */
    PROCESSING("processing"),
    /** 生成成功 */
    SUCCESS("success"),
    /** 生成失败 */
    FAILED("failed");

    private final String code;

    ReportStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
