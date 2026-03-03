package com.fileproc.common;

import lombok.Getter;

/**
 * 业务异常，携带 code 和 message
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = 500;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException of(String message) {
        return new BizException(message);
    }

    public static BizException of(int code, String message) {
        return new BizException(code, message);
    }

    /** 404 */
    public static BizException notFound(String resource) {
        return new BizException(404, resource + " 不存在");
    }

    /** 403 */
    public static BizException forbidden(String message) {
        return new BizException(403, message);
    }

    /** 401 */
    public static BizException unauthorized(String message) {
        return new BizException(401, message);
    }
}
