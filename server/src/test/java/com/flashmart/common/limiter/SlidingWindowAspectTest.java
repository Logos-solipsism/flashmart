package com.flashmart.common.limiter;

import com.flashmart.common.blacklist.BlacklistService;
import com.flashmart.common.blacklist.ClientIpResolver;
import com.flashmart.common.context.UserContext;
import com.flashmart.common.exception.BizException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流切面：key 的三段拼装（业务键 / 维度 / 身份）、被限流时抛 429、
 * 以及"未登录接口取不到上下文时必须用真实 IP"这条容易退化成全局限流的坑。
 * 另加：被限流时记的黑名单违规必须是【客户端 IP】，不是分桶身份（userId / "global"）——
 * 黑名单只按 IP 查，记成 userId 就等于封禁静默失效；放行路径不记。
 */
class SlidingWindowAspectTest {

    private final SlidingWindowLimiter limiter = mock(SlidingWindowLimiter.class);
    private final BlacklistService blacklistService = mock(BlacklistService.class);
    private final SlidingWindowAspect aspect = new SlidingWindowAspect(limiter, blacklistService);

    @SlidingWindowLimit(key = "seckill", windowSeconds = 1, maxRequests = 5,
            dimension = SlidingWindowLimit.Dimension.USER)
    public void userApi() {
    }

    @SlidingWindowLimit(key = "login", windowSeconds = 1, maxRequests = 20,
            dimension = SlidingWindowLimit.Dimension.IP)
    public void ipApi() {
    }

    @SlidingWindowLimit(key = "global_api", windowSeconds = 1, maxRequests = 100,
            dimension = SlidingWindowLimit.Dimension.GLOBAL)
    public void globalApi() {
    }

    private ProceedingJoinPoint pointOf(String methodName) throws Throwable {
        Method method = SlidingWindowAspectTest.class.getMethod(methodName);
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(point.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(point.proceed()).thenReturn("ok");
        return point;
    }

    @AfterEach
    void clear() {
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("用户维度：key 用当前登录用户 ID")
    void userDimensionKey() throws Throwable {
        UserContext.set(new UserContext.LoginUser(9003L, "BUYER", "127.0.0.1"));
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt(), anyString())).thenReturn(true);
        aspect.around(pointOf("userApi"));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(limiter).tryAcquire(key.capture(), anyInt(), anyInt(), anyString());
        assertEquals("rate:seckill:USER:9003", key.getValue());
    }

    @Test
    @DisplayName("IP 维度：未登录接口（上下文为空）必须取真实 IP，不能落到同一个桶")
    void ipDimensionUsesRealIp() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt(), anyString())).thenReturn(true);

        aspect.around(pointOf("ipApi"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(limiter).tryAcquire(key.capture(), anyInt(), anyInt(), anyString());
        assertEquals("rate:login:IP:10.1.2.3", key.getValue());
    }

    @Test
    @DisplayName("全局维度：key 用 global")
    void globalDimensionKey() throws Throwable {
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt(), anyString())).thenReturn(true);
        aspect.around(pointOf("globalApi"));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(limiter).tryAcquire(key.capture(), anyInt(), anyInt(), anyString());
        assertEquals("rate:global_api:GLOBAL:global", key.getValue());
    }

    @Test
    @DisplayName("被限流时抛 429，且业务方法不会被执行")
    void rejectedThrows429() throws Throwable {
        UserContext.set(new UserContext.LoginUser(9003L, "BUYER", "127.0.0.1"));
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt(), anyString())).thenReturn(false);
        ProceedingJoinPoint point = pointOf("userApi");
        BizException error = assertThrows(BizException.class, () -> aspect.around(point));
        assertEquals(429, error.getCode());
        verify(point, org.mockito.Mockito.never()).proceed();
        assertTrue(error.getMessage().contains("频繁"));
    }

    @Test
    @DisplayName("被限流时记的是客户端 IP（IP 维度下身份恰好也等于 IP，只解析一次）")
    void rejectedRecordsViolationWithRequestIp() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt(), anyString())).thenReturn(false);

        assertThrows(BizException.class, () -> aspect.around(pointOf("ipApi")));

        // IP 维度下 clientIp 既当分桶身份也当违规 IP，同一个请求只解析一次
        verify(blacklistService).recordViolation("10.1.2.3");
    }

    @Test
    @DisplayName("USER 维度被限流：分桶按 userId，但违规记在客户端 IP 上（绝不用 userId 记违规）")
    void userDimensionRecordsViolationAgainstIpNotUserId() throws Throwable {
        // 已登录用户：拦截器已经把自己的 IP 解析结果写进上下文
        UserContext.set(new UserContext.LoginUser(9003L, "BUYER", "10.1.2.3"));
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt(), anyString())).thenReturn(false);

        assertThrows(BizException.class, () -> aspect.around(pointOf("userApi")));

        // 分桶身份仍然是 userId（限流 key 结构不许变）
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(limiter).tryAcquire(key.capture(), anyInt(), anyInt(), anyString());
        assertEquals("rate:seckill:USER:9003", key.getValue());

        // 但违规必须记在 IP 上：黑名单只按 IP 查，记成 9003 会写出没人查的死 key
        verify(blacklistService).recordViolation("10.1.2.3");
        verify(blacklistService, org.mockito.Mockito.never()).recordViolation("9003");
    }

    @Test
    @DisplayName("GLOBAL 维度被限流：违规记客户端 IP，绝不记字符串 global")
    void globalDimensionRecordsViolationAgainstIpNotGlobal() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt(), anyString())).thenReturn(false);

        assertThrows(BizException.class, () -> aspect.around(pointOf("globalApi")));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(limiter).tryAcquire(key.capture(), anyInt(), anyInt(), anyString());
        assertEquals("rate:global_api:GLOBAL:global", key.getValue());

        verify(blacklistService).recordViolation("10.1.2.3");
        verify(blacklistService, org.mockito.Mockito.never()).recordViolation("global");
    }

    @Test
    @DisplayName("既没有登录上下文也没有请求上下文：记 unknown-ip 且不抛异常（由 service 侧忽略）")
    void missingContextRecordsUnknownIpWithoutThrowing() throws Throwable {
        RequestContextHolder.resetRequestAttributes();
        UserContext.clear();
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt(), anyString())).thenReturn(false);

        // 429 照常抛出，说明记违规这一步没有把异常带出来
        assertThrows(BizException.class, () -> aspect.around(pointOf("userApi")));

        verify(blacklistService).recordViolation(ClientIpResolver.UNKNOWN_IP);
    }

    @Test
    @DisplayName("放行路径不记违规（也不新增任何 Redis 往返）")
    void allowedDoesNotRecordViolation() throws Throwable {
        UserContext.set(new UserContext.LoginUser(9003L, "BUYER", "127.0.0.1"));
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt(), anyString())).thenReturn(true);

        aspect.around(pointOf("userApi"));

        verify(blacklistService, org.mockito.Mockito.never()).recordViolation(anyString());
    }
}
