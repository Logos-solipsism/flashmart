package com.flashmart.common.context;

/**
 * 当前登录用户上下文（由 JWT 拦截器写入）。
 * 一秒杀资格校验、限流维度、订单归属都依赖它，避免前端伪造 userId。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long userId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    public static String role() {
        LoginUser user = HOLDER.get();
        return user == null ? "GUEST" : user.role();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public record LoginUser(Long userId, String role, String clientIp) {
    }
}
