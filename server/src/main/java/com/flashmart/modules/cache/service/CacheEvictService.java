package com.flashmart.modules.cache.service;

import com.flashmart.common.cache.RedisCacheClient;
import com.flashmart.common.mq.MqEvents;
import com.flashmart.common.mq.MqProducer;
import com.flashmart.common.mq.MqTopics;
import com.flashmart.modules.cache.entity.CacheSyncTask;
import com.flashmart.modules.cache.mapper.CacheSyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 缓存一致性策略：Cache Aside（先更新数据库、再删缓存）+ 补偿重试 + 延迟双删 + TTL 兜底。
 *
 * <p>为什么要"删缓存"而不是"更新缓存"：并发更新时，两个请求的写入顺序无法保证，
 * 更新缓存可能出现"后写的旧值覆盖先写的新值"；删除则不会有这个问题，下次读自然回源。
 *
 * <p>为什么还要"延迟双删"：极端并发下（读请求在删缓存之前查到旧值、在删缓存之后才写回缓存），
 * 缓存里会残留旧值。延迟一小段时间再删一次，就能把这种残留清掉。
 *
 * <p>删除失败怎么办：落一条补偿记录 + 投递 MQ，由消费者重试删除；即使重试也全失败，
 * 缓存本身还有 TTL 兜底，最终仍会一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheEvictService {

    private final RedisCacheClient cacheClient;
    private final CacheSyncTaskMapper cacheSyncTaskMapper;
    private final MqProducer mqProducer;

    @Value("${flashmart.cache.double-delete-delay-seconds:5}")
    private int doubleDeleteDelaySeconds;

    public void evictWithCompensation(String cacheKey) {
        evict(cacheKey);
        // 延迟双删：延迟一段时间再删一次，清掉并发读可能回填的旧值
        mqProducer.sendAsync(MqTopics.CACHE_EVICT, "cache:" + cacheKey,
                new MqEvents.CacheEvictEvent(cacheKey, "DOUBLE_DELETE"),
                MqProducer.nearestDelayLevel(doubleDeleteDelaySeconds));
    }

    /** 删除缓存；失败则落补偿记录并投递重试消息 */
    public void evict(String cacheKey) {
        if (cacheClient.evict(cacheKey)) {
            return;
        }
        log.warn("删缓存失败，落补偿任务 cacheKey={}", cacheKey);
        CacheSyncTask task = new CacheSyncTask();
        task.setCacheKey(cacheKey);
        task.setStatus(CacheSyncTask.STATUS_PENDING);
        task.setRetryCount(0);
        task.setErrorMsg("");
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        cacheSyncTaskMapper.insert(task);
        mqProducer.sendAsync(MqTopics.CACHE_EVICT, "cache:" + cacheKey,
                new MqEvents.CacheEvictEvent(cacheKey, "UPDATE"), 0);
    }
}
