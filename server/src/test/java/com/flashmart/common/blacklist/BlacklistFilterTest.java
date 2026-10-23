package com.flashmart.common.blacklist;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 黑名单过滤器：被封的请求必须在 Filter 层就被 429 拦下（chain 一次都不能调）、
 * 放行口径（未封 / 非 /api/ 路径 / 开关关闭 / Redis 异常）以及客户端 IP 的解析优先级。
 */
class BlacklistFilterTest {

    private final BlacklistService blacklistService = mock(BlacklistService.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final BlacklistFilter filter = new BlacklistFilter(blacklistService);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(filter, "enabled", true);
    }

    private MockHttpServletRequest apiRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/seckill/9001");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    @Test
    @DisplayName("被黑名单命中的 IP：直接 429 + code=429 的 JSON 响应体，chain 一次都没被调用")
    void bannedIpRejectedWithoutChain() throws Exception {
        MockHttpServletRequest request = apiRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        MockHttpServletResponse response = response();
        when(blacklistService.isBanned("203.0.113.7")).thenReturn(true);

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        assertEquals("{\"code\":429,\"message\":\"访问过于频繁，已被临时限制，请稍后再试\"}", response.getContentAsString());
        verify(chain, never()).doFilter(request, response);
        verify(blacklistService).recordRejected();
    }

    @Test
    @DisplayName("未被封的 IP：chain 恰好被调用一次")
    void normalIpPassesThroughOnce() throws Exception {
        MockHttpServletRequest request = apiRequest();
        MockHttpServletResponse response = response();
        when(blacklistService.isBanned("127.0.0.1")).thenReturn(false);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(blacklistService, never()).recordRejected();
    }

    @Test
    @DisplayName("非 /api/ 路径：直接放行且不查 Redis")
    void nonApiPathBypassesBlacklist() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/doc.html");
        MockHttpServletResponse response = response();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(blacklistService, never()).isBanned(anyString());
    }

    @Test
    @DisplayName("enabled=false：放行且不查 Redis")
    void disabledFilterPassesThrough() throws Exception {
        ReflectionTestUtils.setField(filter, "enabled", false);
        MockHttpServletRequest request = apiRequest();
        MockHttpServletResponse response = response();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(blacklistService, never()).isBanned(anyString());
    }

    @Test
    @DisplayName("Redis 异常：fail-open，请求照常放行")
    void redisErrorFailsOpen() throws Exception {
        MockHttpServletRequest request = apiRequest();
        MockHttpServletResponse response = response();
        when(blacklistService.isBanned("127.0.0.1")).thenThrow(new RuntimeException("redis down"));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("IP 解析：有 X-Forwarded-For 时取第一段（客户端可伪造，已知弱点）")
    void resolvesFirstSegmentOfForwardedFor() throws Exception {
        MockHttpServletRequest request = apiRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

        filter.doFilter(request, response(), chain);

        verify(blacklistService).isBanned("203.0.113.7");
    }

    @Test
    @DisplayName("IP 解析：没有 X-Forwarded-For 时退回 remoteAddr")
    void fallsBackToRemoteAddr() throws Exception {
        MockHttpServletRequest request = apiRequest();

        filter.doFilter(request, response(), chain);

        verify(blacklistService).isBanned("127.0.0.1");
    }
}
