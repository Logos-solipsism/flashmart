package com.flashmart.common.blacklist;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * IP 黑名单过滤器：把已封禁的客户端挡在限流切面之前——一个 Redis GET 换掉整段 Lua。
 *
 * <p><b>为什么是 HIGHEST_PRECEDENCE + 1</b>：TraceIdFilter 占了 HIGHEST_PRECEDENCE，
 * 必须让它先跑、把 traceId 写进 MDC，本过滤器打的拒绝日志才带得上 traceId
 * （排查"这个 IP 什么时候被封的"全靠它）。排数字上紧随其后即是最早的可插位置。
 *
 * <p>只对 /api/** 生效：Swagger 文档页、静态资源不该被 IP 黑名单影响。
 *
 * <p><b>已知弱点（本次刻意不修）</b>：客户端 IP 来自 X-Forwarded-For 的第一段，
 * 而该请求头可被客户端伪造，所以攻击者换着伪造 IP 就能绕开按 IP 维度的封禁。
 * 根治要在最外层网关剥离外来的 XFF 并写入可信头，应用侧只读可信头——部署层改动，超出本轮范围。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class BlacklistFilter extends OncePerRequestFilter {

    private static final String REJECTED_BODY =
            "{\"code\":429,\"message\":\"访问过于频繁，已被临时限制，请稍后再试\"}";

    private final BlacklistService blacklistService;

    /** 关掉后连 Redis 都不查 */
    @Value("${flashmart.blacklist.enabled:true}")
    private boolean enabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (enabled && isApiRequest(request.getRequestURI())) {
                String ip = ClientIpResolver.resolve(request);
                if (blacklistService.isBanned(ip)) {
                    blacklistService.recordRejected();
                    log.warn("IP 在黑名单中，请求被直接拒绝(未进入限流), ip={}, uri={}", ip, request.getRequestURI());
                    reject(response);
                    return;
                }
            }
        } catch (Exception e) {
            // fail-open：黑名单本身出问题绝不能把正常请求挡掉
            log.warn("黑名单过滤器异常，放行本次请求, uri={}, cause={}", request.getRequestURI(), e.getMessage());
        }
        chain.doFilter(request, response);
    }

    private boolean isApiRequest(String uri) {
        return uri != null && uri.startsWith("/api/");
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(REJECTED_BODY);
    }
}
