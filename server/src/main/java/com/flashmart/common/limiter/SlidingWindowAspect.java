package com.flashmart.common.limiter;

import com.flashmart.common.blacklist.BlacklistService;
import com.flashmart.common.blacklist.ClientIpResolver;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
    private final BlacklistService blacklistService;

    /**
     * 当前请求的客户端 IP：优先用拦截器写进上下文的（JWT 鉴权时已经解析过一次），
     * 没有就现取（未登录接口）。解析规则收敛在 ClientIpResolver，禁止在这里再写第二份。
     */
    private String currentClientIp() {
        UserContext.LoginUser loginUser = UserContext.get();
        if (loginUser != null && loginUser.clientIp() != null) {
            return String.valueOf(loginUser.clientIp());
        }
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return ClientIpResolver.UNKNOWN_IP;
            }
            return ClientIpResolver.resolve(attributes.getRequest());
        } catch (Exception e) {
            return ClientIpResolver.UNKNOWN_IP;
        }
    }

    @Around("@annotation(com.flashmart.common.limiter.SlidingWindowLimit)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        Method method = ((MethodSignature) point.getSignature()).getMethod();
        SlidingWindowLimit annotation = method.getAnnotation(SlidingWindowLimit.class);

        String identity = switch (annotation.dimension()) {
            case USER -> String.valueOf(UserContext.userId() == null ? "anonymous" : UserContext.userId());
            // 未登录接口（比如登录、注册）走不到拦截器，UserContext 是空的，
            // 这时必须从当前 HTTP 请求里取真实 IP —— 否则所有客户端的登录请求会挤进同一个桶，
            // 变成"全局限流"，一个人刷就能把别人挡住。
            case IP -> currentClientIp();
            case GLOBAL -> "global";
        };

        String bucketKey = "rate:" + annotation.key() + ":" + annotation.dimension() + ":" + identity;
        // 成员用 traceId + 随机串：同一毫秒内的并发请求也不会互相覆盖
        String member = TraceUtil.current() + "-" + UUID.randomUUID();

        boolean allowed = limiter.tryAcquire(bucketKey, annotation.windowSeconds(),
                annotation.maxRequests(), member);
        if (!allowed) {
            // 被限流即计一次违规：同一个 IP 反复被限流说明它在刷，累计到阈值就提前封掉，
            // 让后续请求在 BlacklistFilter 层就被拒——不用再跑这段 Lua。
            // 失败在 BlacklistService 内部被吞掉（fail-open），不会影响 429 的抛出。
            blacklistService.recordViolation(identity);
            log.warn("触发滑动窗口限流, key={}, method={}", bucketKey, method.getName());
            throw BizException.tooManyRequests(annotation.message());
        }
        return point.proceed();
    }
}
