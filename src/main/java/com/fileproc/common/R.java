package com.fileproc.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体，与前端 ApiResponse<T> 字段完全对齐
 */
@Data
public class R<T> implements Serializable {

    private int code;
    private String message;
    private T data;

    private R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功（含数据） */
    public static <T> R<T> ok(T data) {
        return new R<>(200, "success", data);
    }

    /** 成功（带自定义消息） */
    public static <T> R<T> ok(String message, T data) {
        return new R<>(200, message, data);
    }

    /** 成功（无数据） */
    public static <T> R<T> ok() {
        return new R<>(200, "success", null);
    }

    /** 失败（自定义code） */
    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    /** 失败（code=500） */
    public static <T> R<T> fail(String message) {
        return new R<>(500, message, null);
    }

    /** 未授权 */
    public static <T> R<T> unauthorized(String message) {
        return new R<>(401, message, null);
    }

    /** 禁止访问 */
    public static <T> R<T> forbidden(String message) {
        return new R<>(403, message, null);
    }

    public boolean isOk() {
        return this.code == 200;
    }
}
