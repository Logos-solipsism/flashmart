package com.flashmart.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求入口写 traceId：优先用调用方传来的 X-Trace-Id（压测工具会带上），
 * 否则自己生成，并原样写回响应头，方便定位"这一次请求"的全部日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceUtil.start(request.getHeader(TraceUtil.HEADER));
        response.setHeader(TraceUtil.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceUtil.clear();
        }
    }
}
