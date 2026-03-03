package com.fileproc.auth.util;

/**
 * Token 相关公共常量
 * P2：抽取黑名单前缀常量，供 AuthService / AdminAuthService 统一引用
 */
public final class TokenConstants {

    private TokenConstants() {}

    /** Redis 黑名单 key 前缀 */
    public static final String BLACKLIST_PREFIX = "token:blacklist:";
}
