package com.flashmart.common.limiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 滑动窗口限流器：ZSet 存"请求时间戳"，用 Lua 保证"清理过期成员 → 统计 → 写入"三步原子执行。
 * 不原子的话，并发下会出现"都统计到没超限、于是都放行"的经典超发现象。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlidingWindowLimiter {

    private final StringRedisTemplate redis;

    /** Lua 实际执行次数：压测用它证明"黑名单命中时一次 Lua 都没跑"（零网络开销的进程内计数） */
    private final AtomicLong callCount = new AtomicLong();

    private static final RedisScript<Long> SCRIPT = script();

    private static RedisScript<Long> script() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/sliding_window.lua")));
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    /**
     * @return true = 放行；false = 被限流
     */
    public boolean tryAcquire(String key, int windowSeconds, int maxRequests, String member) {
        callCount.incrementAndGet();
        try {
            Long result = redis.execute(SCRIPT, List.of(key),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(windowSeconds),
                    String.valueOf(maxRequests),
                    member);
            return result != null && result == 1L;
        } catch (Exception e) {
            // Redis 不可用时放行：限流是保护手段，不能因为限流组件自己挂了把业务熔断
            log.warn("滑动窗口限流降级放行 key={}, cause={}", key, e.getMessage());
            return true;
        }
    }

    /** 已执行的 Lua 次数（每次 tryAcquire 调用 +1，含降级放行） */
    public long callCount() {
        return callCount.get();
    }

    /** 供压测口子清零 */
    public void resetCallCount() {
        callCount.set(0);
    }
}
