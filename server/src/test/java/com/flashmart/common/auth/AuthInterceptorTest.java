package com.flashmart.common.auth;

import com.flashmart.common.context.UserContext;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录拦截器写进上下文的客户端 IP：必须走 ClientIpResolver（全项目唯一实现），
 * 且"取不到就兜底 unknown-ip"，绝不能是空串。
 *
 * <p>空串是个真实的坑：限流切面只判 {@code != null}，空身份会让所有这类请求挤进同一个桶
 * （{@code rate:login:IP:}），退化成"全局限流"——文档里反复强调要避免的那件事。
 */
class AuthInterceptorTest {

    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Claims claims = mock(Claims.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(jwtUtil, redis);

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    /** 让 JWT 解析与会话校验都通过，把断言聚焦在"IP 写成了什么"上 */
    private void stubSuccessfulAuth() {
        when(jwtUtil.parse("t")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("9003");
        when(claims.get("role")).thenReturn("BUYER");
        when(redis.hasKey("session:token:9003")).thenReturn(true);
    }

    @Test
    @DisplayName("remoteAddr 为空且没有 X-Forwarded-For：上下文里的 IP 是 unknown-ip，不是空串")
    void emptyRemoteAddrFallsBackToUnknownIp() throws Exception {
        stubSuccessfulAuth();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer t");
        request.setRemoteAddr("");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        UserContext.LoginUser loginUser = UserContext.get();
        assertNotNull(loginUser);
        assertEquals("unknown-ip", loginUser.clientIp());
        verify(redis).expire("session:token:9003", 24, TimeUnit.HOURS);
    }

    @Test
    @DisplayName("有 X-Forwarded-For：取第一段（与黑名单过滤器、限流切面同一套规则）")
    void forwardedForFirstSegmentIsUsed() throws Exception {
        stubSuccessfulAuth();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer t");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        request.setRemoteAddr("127.0.0.1");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals("203.0.113.7", UserContext.get().clientIp());
    }

    @Test
    @DisplayName("没有 X-Forwarded-For：用 remoteAddr")
    void remoteAddrIsUsedWithoutForwardedFor() throws Exception {
        stubSuccessfulAuth();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer t");
        request.setRemoteAddr("10.1.2.3");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals("10.1.2.3", UserContext.get().clientIp());
    }
}
