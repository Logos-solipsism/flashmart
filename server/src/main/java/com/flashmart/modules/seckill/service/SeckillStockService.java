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
import java.util.function.IntSupplier;

/**
 * 抢购库存：Redis 是扣减的真身，数据库只做异步回写与最终兜底。
 *
 * <p>三个 key：
 * <ul>
 *   <li><code>seckill:stock:{id}</code> 剩余库存（扣减的真身）</li>
 *   <li><code>seckill:total:{id}</code> 活动总库存（回滚时的上限，防止把库存加超）</li>
 *   <li><code>seckill:bought:{id}</code> 已购记录（Hash：userId -&gt; 已购数量，一人一单靠它）</li>
 * </ul>
 *
 * <p>返回值约定（来自 lua/seckill_stock.lua）：
 * &gt;=0 扣减成功后的剩余库存；-1 库存不足；-2 超过限购；-3 活动未预热。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillStockService {

    public static final long SOLD_OUT = -1L;
    public static final long OVER_LIMIT = -2L;
    public static final long NOT_PREPARED = -3L;

    private final StringRedisTemplate redis;

    private static final RedisScript<Long> SECKILL_SCRIPT = script("lua/seckill_stock.lua");
    private static final RedisScript<Long> ROLLBACK_SCRIPT = script("lua/rollback_stock.lua");

    private static RedisScript<Long> script(String path) {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    /**
     * 幂等预热：Redis 里没有库存 key 时，从数据库读<b>当前剩余库存</b>写进去。
     *
     * <p>为什么必须读 available_stock 而不是 total_stock：库存 key 一旦丢失（被误删、过期），
     * 按总库存重建会把已经卖出去的部分"补回来"，凭空多出库存 —— 这比超卖还难查。
     *
     * <p>为什么还要一起写 total：回滚库存时需要一个上限，否则来迟的关单消息
     * （比如压测重置之后才到的在途消息）会把库存加超总量。
     *
     * <p>SETNX 保证同一个活动只初始化一次，避免并发把已经扣减过的库存覆盖回去。
     */
    public void ensurePrepared(Long activityId, IntSupplier dbAvailableStock, int totalStock) {
        // 快路径：库存 key 已经在了就直接返回。
        // 抢购接口每个请求都会调这里，如果每次都对初始化锁做 SETNX，
        // 不仅每个请求多一次写操作，还会在锁 TTL 过期后出现"用数据库库存覆盖 Redis 计数器"的窗口。
        if (Boolean.TRUE.equals(redis.hasKey(ActivityCacheKeys.stock(activityId)))) {
            return;
        }
        Boolean got = redis.opsForValue()
                .setIfAbsent(ActivityCacheKeys.stockInit(activityId), "1", Duration.ofSeconds(10));
        if (Boolean.TRUE.equals(got)) {
            redis.opsForValue().set(ActivityCacheKeys.stock(activityId),
                    String.valueOf(dbAvailableStock.getAsInt()), Duration.ofDays(7));
            redis.opsForValue().set(ActivityCacheKeys.total(activityId),
                    String.valueOf(totalStock), Duration.ofDays(7));
        }
    }

    /** 强制覆盖库存（商家改活动、压测前重置场景） */
    public void reset(Long activityId, int availableStock, int totalStock) {
        redis.opsForValue().set(ActivityCacheKeys.stock(activityId),
                String.valueOf(availableStock), Duration.ofDays(7));
        redis.opsForValue().set(ActivityCacheKeys.total(activityId),
                String.valueOf(totalStock), Duration.ofDays(7));
        redis.delete(ActivityCacheKeys.bought(activityId));
    }

    public int currentStock(Long activityId, int fallback) {
        String value = redis.opsForValue().get(ActivityCacheKeys.stock(activityId));
        return value == null ? fallback : Integer.parseInt(value);
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

    /**
     * 只回滚库存（超时关单时用）。
     * 注意这里是<b>故意不回滚已购记录</b>的：名额已经用掉了，用户不付款就作废，
     * 库存退回池子里给其他人抢 —— 这正是真实秒杀的做法（限购机会只有一次）。
     * 回滚带上限（seckill:total:{id}），避免在途消息把库存加超。
     */
    public void rollbackStock(Long activityId) {
        redis.execute(ROLLBACK_SCRIPT,
                List.of(ActivityCacheKeys.stock(activityId), ActivityCacheKeys.total(activityId)));
    }

    /**
     * 回滚库存 + 撤销已购记录（下单失败时用）。
     * 这种场景用户什么都没拿到（订单根本没落库），所以要把他这次的机会还回去。
     */
    public void rollbackAll(Long activityId, Long userId) {
        rollbackStock(activityId);
        // 只减不增到负数：HINCRBY -1 在并发重试下可能减成 -1，这里兜一下
        Object value = redis.opsForHash().get(ActivityCacheKeys.bought(activityId), String.valueOf(userId));
        if (value != null && Long.parseLong(String.valueOf(value)) > 0) {
            redis.opsForHash().increment(ActivityCacheKeys.bought(activityId), String.valueOf(userId), -1);
        }
    }
}
