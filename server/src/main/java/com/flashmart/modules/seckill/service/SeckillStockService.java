package com.flashmart.modules.seckill.service;

import com.flashmart.modules.activity.service.ActivityCacheKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 抢购库存：Redis 是扣减的真身，数据库只做异步回写与最终兜底。
 *
 * <p>三个返回值约定（来自 lua/seckill_stock.lua）：
 * >=0 扣减成功后的剩余库存；-1 库存不足；-2 超过限购；-3 活动未预热。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillStockService {

    public static final long SOLD_OUT = -1L;
    public static final long OVER_LIMIT = -2L;
    public static final long NOT_PREPARED = -3L;

    private final StringRedisTemplate redis;

    private static final RedisScript<Long> SECKILL_SCRIPT = script();

    private static RedisScript<Long> script() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill_stock.lua")));
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    /** 幂等预热：同一个活动只初始化一次，避免并发把已经扣减过的库存又覆盖回去 */
    public void prepare(Long activityId, int availableStock) {
        Boolean got = redis.opsForValue()
                .setIfAbsent(ActivityCacheKeys.stockInit(activityId), "1", Duration.ofSeconds(10));
        if (Boolean.TRUE.equals(got)) {
            redis.opsForValue().set(ActivityCacheKeys.stock(activityId),
                    String.valueOf(availableStock), Duration.ofDays(7));
        }
    }

    /** 强制覆盖库存（商家改活动、压测前重置场景） */
    public void reset(Long activityId, int availableStock) {
        redis.opsForValue().set(ActivityCacheKeys.stock(activityId),
                String.valueOf(availableStock), Duration.ofDays(7));
        redis.delete(ActivityCacheKeys.bought(activityId));
    }

    public int currentStock(Long activityId, int fallback) {
        String value = redis.opsForValue().get(ActivityCacheKeys.stock(activityId));
        return value == null ? fallback : Integer.parseInt(value);
    }

    /** 撤回某个用户的已购记录（超时关单后允许他重新抢） */
    public void clearUserRecord(Long activityId, Long userId) {
        redis.opsForHash().delete(ActivityCacheKeys.bought(activityId), String.valueOf(userId));
    }

    /**
     * 原子抢购：判断限购 + 判断库存 + 扣库存 一次完成（见 lua 脚本注释）。
     */
    public long trySeckill(Long activityId, Long userId, int limitPerUser) {
        Long result = redis.execute(SECKILL_SCRIPT,
                List.of(ActivityCacheKeys.stock(activityId), ActivityCacheKeys.bought(activityId)),
                String.valueOf(userId),
                String.valueOf(limitPerUser));
        return result == null ? NOT_PREPARED : result;
    }

    /** 回滚：库存 +1，已购记录 -1（下单失败或超时关单时调用） */
    public void rollback(Long activityId, Long userId) {
        redis.opsForValue().increment(ActivityCacheKeys.stock(activityId));
        redis.opsForHash().increment(ActivityCacheKeys.bought(activityId), String.valueOf(userId), -1);
    }
}
