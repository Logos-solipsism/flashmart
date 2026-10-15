package com.flashmart.common.limiter;

import com.flashmart.common.context.UserContext;
import com.flashmart.common.exception.BizException;
import com.flashmart.common.trace.TraceUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 滑动窗口限流切面。
 * key 组合规则：rate:{业务键}:{维度}:{身份}，例如 rate:seckill:USER:9003
 * 身份一律取自服务端上下文，绝不用请求参数——否则攻击者传别人的 ID 就能把别人的额度耗光。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class SlidingWindowAspect {

    private final SlidingWindowLimiter limiter;

    @Around("@annotation(com.flashmart.common.limiter.SlidingWindowLimit)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        Method method = ((MethodSignature) point.getSignature()).getMethod();
        SlidingWindowLimit annotation = method.getAnnotation(SlidingWindowLimit.class);

        String identity = switch (annotation.dimension()) {
            case USER -> String.valueOf(UserContext.userId() == null ? "anonymous" : UserContext.userId());
            case IP -> {
                UserContext.LoginUser loginUser = UserContext.get();
                yield loginUser == null ? "unknown-ip" : String.valueOf(loginUser.clientIp());
            }
            case GLOBAL -> "global";
        };

        String bucketKey = "rate:" + annotation.key() + ":" + annotation.dimension() + ":" + identity;
        // 成员用 traceId + 随机串：同一毫秒内的并发请求也不会互相覆盖
        String member = TraceUtil.current() + "-" + UUID.randomUUID();

        boolean allowed = limiter.tryAcquire(bucketKey, annotation.windowSeconds(),
                annotation.maxRequests(), member);
        if (!allowed) {
            log.warn("触发滑动窗口限流, key={}, method={}", bucketKey, method.getName());
            throw BizException.tooManyRequests(annotation.message());
        }
        return point.proceed();
    }
}
