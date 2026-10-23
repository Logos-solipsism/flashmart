package com.flashmart.common.blacklist;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析：全项目唯一的「取客户端 IP」实现。
 *
 * <p>黑名单过滤器和限流切面必须用同一套解析规则——否则黑名单按一个 IP 封，
 * 限流按另一个 IP 计数，两边对不上，封了等于没封。
 *
 * <p><b>已知弱点（本次刻意不修）</b>：X-Forwarded-For 是客户端可自由伪造的请求头，
 * 所以攻击者只要每次请求换一个伪造 IP，就能绕开按 IP 维度的黑名单/限流。
 * 要真正修，需要在最外层网关（Nginx/ELB）剥离外部传来的 XFF 并写入可信头，
 * 应用侧只读可信头；这属于部署层改动，超出本轮范围，故只记录不实现。
 */
public final class ClientIpResolver {

    /** 取不到任何 IP 时的兜底值：绝不能返回 null，否则会被拼进 Redis key 变成 black:list:null */
    public static final String UNKNOWN_IP = "unknown-ip";

    private ClientIpResolver() {
    }

    /**
     * 优先取 X-Forwarded-For 的第一段（最靠近客户端的那个地址），
     * 没有就用 getRemoteAddr()，再取不到返回 unknown-ip。
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN_IP;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return firstSegment(forwarded);
        }
        return normalize(request.getRemoteAddr());
    }

    private static String firstSegment(String value) {
        int comma = value.indexOf(',');
        return normalize(comma >= 0 ? value.substring(0, comma) : value);
    }

    private static String normalize(String value) {
        if (value == null) {
            return UNKNOWN_IP;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? UNKNOWN_IP : trimmed;
    }
}
