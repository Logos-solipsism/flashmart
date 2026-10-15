package com.flashmart.common.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 热点数据的缓存读写，解决三个经典问题：
 *
 * <p>1) <b>缓存穿透</b>：查不到的数据也写一份「空值标记」并设置短 TTL，
 *    这样同一批不存在的 id 只会打一次数据库；
 * <p>2) <b>缓存击穿</b>：热点 key 用「逻辑过期」——缓存里额外存一个逻辑过期时间，
 *    逻辑过期后<b>不删 key</b>，而是返回旧值并由一个后台线程异步重建，
 *    重建期间所有请求都能拿到数据、不会被阻塞（对比：互斥锁方案会让请求排队等待）；
 * <p>3) <b>缓存一致性</b>：更新数据时由调用方「先更新数据库、再删缓存」，
 *    删除失败则投递消息让消费者重试（见 CacheEvictService）。
 *
 * <p>重建用 SETNX 做互斥：保证同一时刻只有一个线程去回源，其他请求直接拿旧值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheClient {

    /** 空值标记：命中它说明"数据库里确实没有这条数据"，用来防止穿透 */
    private static final String NULL_MARK = "__NULL__";

    /** 重建互斥锁 key 前缀 */
    private static final String REBUILD_LOCK_PREFIX = "cache:rebuild:lock:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${flashmart.cache.null-value-ttl-seconds:30}")
    private long nullValueTtlSeconds;

    /** 后台重建线程池：重建是"尽力而为"的动作，失败也不影响本次请求 */
    private final ExecutorService rebuildPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "cache-rebuild");
        t.setDaemon(true);
        return t;
    });

    /**
     * 按「逻辑过期」策略读取缓存。
     *
     * @param key              缓存 key
     * @param type             返回值类型
     * @param logicTtlSeconds  逻辑过期时间（秒）：超过它就会触发异步重建
     * @param loader           回源函数（查数据库）
     * @return 缓存命中返回缓存值；命中空值标记返回 null；未命中返回 null 并写入空值标记
     */
    public <T> T getWithLogicExpire(String key, Class<T> type, long logicTtlSeconds, Supplier<T> loader) {
        String cached = redis.opsForValue().get(key);

        // 1) 命中空值标记：数据库里没有这条数据，直接返回，不再回源（防穿透）
        if (NULL_MARK.equals(cached)) {
            return null;
        }

        // 2) 完全未命中（缓存刚被删或从没写过）：用 SETNX 只放一个线程回源，其余短暂等待后重读缓存。
        //    不这样做的话，热点 key 被删的瞬间会有 N 个并发请求同时打到数据库（冷启动击穿）。
        if (cached == null) {
            String lockKey = REBUILD_LOCK_PREFIX + key;
            Boolean got = redis.opsForValue().setIfAbsent(lockKey, UUID.randomUUID().toString(), Duration.ofSeconds(10));
            if (Boolean.TRUE.equals(got)) {
                try {
                    countLoad(key);
                    T loaded = loader.get();
                    if (loaded == null) {
                        redis.opsForValue().set(key, NULL_MARK, Duration.ofSeconds(nullValueTtlSeconds));
                        return null;
                    }
                    writeWithLogicExpire(key, loaded, logicTtlSeconds);
                    return loaded;
                } finally {
                    redis.delete(lockKey);
                }
            }
            // 没抢到锁：等一小会儿再读缓存（此时别人正在重建），仍读不到才自己兜底回源
            sleepQuietly(60);
            String retry = redis.opsForValue().get(key);
            if (NULL_MARK.equals(retry)) {
                return null;
            }
            if (retry != null) {
                try {
                    JsonNode node = objectMapper.readTree(retry);
                    return objectMapper.treeToValue(node.path("data"), type);
                } catch (Exception ignored) {
                    // 解析失败就往下走，自己回源
                }
            }
            countLoad(key);
            return loader.get();
        }

        // 3) 命中：判断逻辑过期时间
        try {
            JsonNode node = objectMapper.readTree(cached);
            long expireAt = node.path("expireAt").asLong(0);
            T data = objectMapper.treeToValue(node.path("data"), type);
            if (System.currentTimeMillis() > expireAt) {
                // 逻辑已过期：先返回旧值，再异步重建（不阻塞当前请求，也不会出现缓存击穿）
                asyncRebuild(key, type, logicTtlSeconds, loader);
            }
            return data;
        } catch (Exception e) {
            log.warn("缓存反序列化失败，按未命中处理 key={}, cause={}", key, e.getMessage());
            T loaded = loader.get();
            if (loaded != null) {
                writeWithLogicExpire(key, loaded, logicTtlSeconds);
            }
            return loaded;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 写入带逻辑过期时间的缓存；物理 TTL 给逻辑过期的 3 倍，保证逻辑过期后旧值还在，能继续兜住流量 */
    public void writeWithLogicExpire(String key, Object value, long logicTtlSeconds) {
        try {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.put("expireAt", System.currentTimeMillis() + logicTtlSeconds * 1000);
            wrapper.set("data", objectMapper.valueToTree(value));
            redis.opsForValue().set(key, objectMapper.writeValueAsString(wrapper),
                    Duration.ofSeconds(logicTtlSeconds * 3));
        } catch (Exception e) {
            log.warn("写缓存失败 key={}, cause={}", key, e.getMessage());
        }
    }

    /**
     * 统计某个 key 回源数据库的次数（只回源时才 +1，开销可以忽略）。
     * 用途：验证"缓存击穿时只回源一次""不存在的 id 只回源一次"，把结论变成可复现的数字。
     */
    private void countLoad(String key) {
        try {
            redis.opsForValue().increment("cache:loadcount:" + key);
        } catch (Exception ignored) {
            // 计数失败不影响主流程
        }
    }

    /** 查询某个 key 的累计回源次数（压测脚本用） */
    public long loadCount(String key) {
        String value = redis.opsForValue().get("cache:loadcount:" + key);
        return value == null ? 0 : Long.parseLong(value);
    }

    /** 删除缓存（改数据时用）；返回是否删除成功，失败由调用方走补偿 */
    public boolean evict(String key) {
        try {
            redis.delete(key);
            return true;
        } catch (Exception e) {
            log.warn("删除缓存失败，需要补偿 key={}, cause={}", key, e.getMessage());
            return false;
        }
    }

    private <T> void asyncRebuild(String key, Class<T> type, long logicTtlSeconds, Supplier<T> loader) {
        String lockKey = REBUILD_LOCK_PREFIX + key;
        // SETNX 互斥：只有一个线程能拿到锁去回源，其他请求继续用旧值
        Boolean got = redis.opsForValue().setIfAbsent(lockKey, UUID.randomUUID().toString(), Duration.ofSeconds(10));
        if (!Boolean.TRUE.equals(got)) {
            return;
        }
        rebuildPool.submit(() -> {
            try {
                log.info("缓存逻辑过期，异步重建 key={}", key);
                countLoad(key);
                T loaded = loader.get();
                if (loaded != null) {
                    writeWithLogicExpire(key, loaded, logicTtlSeconds);
                } else {
                    redis.opsForValue().set(key, NULL_MARK, Duration.ofSeconds(nullValueTtlSeconds));
                }
            } catch (Exception e) {
                log.warn("异步重建缓存失败 key={}, cause={}", key, e.getMessage());
            } finally {
                redis.delete(lockKey);
            }
        });
    }
}
