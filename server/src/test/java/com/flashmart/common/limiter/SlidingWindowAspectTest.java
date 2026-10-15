package com.flashmart.common.limiter;

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
 */
class SlidingWindowAspectTest {

    private final SlidingWindowLimiter limiter = mock(SlidingWindowLimiter.class);
    private final SlidingWindowAspect aspect = new SlidingWindowAspect(limiter);

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
}
