package com.flashmart.common.limiter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 滑动窗口限流注解。
 * 与令牌桶的区别：滑动窗口按"最近 N 秒内的请求数"精确统计，不做突发额度的预支，
 * 所以更适合"防脚本刷单、防接口被爬"这种要求精确限次的场景。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SlidingWindowLimit {

    /** 业务键，如 seckill、order_pay */
    String key();

    /** 窗口大小（秒） */
    int windowSeconds() default 1;

    /** 窗口内允许的最大请求数 */
    int maxRequests() default 10;

    /** 限流维度 */
    Dimension dimension() default Dimension.USER;

    String message() default "操作过于频繁，请稍后再试";

    enum Dimension {
        /** 全局维度：保护整个接口或下游资源 */
        GLOBAL,
        /** 客户端 IP 维度：防止单机脚本刷 */
        IP,
        /** 登录用户维度：防止单账号刷 */
        USER
    }
}
