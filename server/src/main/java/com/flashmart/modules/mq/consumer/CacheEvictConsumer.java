package com.flashmart.modules.mq.consumer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashmart.common.cache.RedisCacheClient;
import com.flashmart.common.mq.MqEvents;
import com.flashmart.common.mq.MqIdempotentRepository;
import com.flashmart.common.mq.MqTopics;
import com.flashmart.common.trace.TraceIdFilter;
import com.flashmart.common.trace.TraceUtil;
import com.flashmart.modules.cache.entity.CacheSyncTask;
import com.flashmart.modules.cache.mapper.CacheSyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 缓存删除补偿消费者。
 * 两类消息走同一条链路：
 * ① UPDATE：删缓存失败了，重试删除，成功后把补偿任务标记为 SUCCESS；
 * ② DOUBLE_DELETE：延迟双删，把并发读可能回填的旧值再清一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.CACHE_EVICT, consumerGroup = MqTopics.CG_CACHE_EVICT,
        consumeThreadMax = 32)
public class CacheEvictConsumer implements RocketMQListener<MessageExt> {

    private final ObjectMapper objectMapper;
    private final MqIdempotentRepository idempotentRepository;
    private final RedisCacheClient cacheClient;
    private final CacheSyncTaskMapper cacheSyncTaskMapper;

    @Override
    public void onMessage(MessageExt message) {
        TraceUtil.start(message.getUserProperty(TraceUtil.HEADER));
        try {
            handle(message);
        } finally {
            TraceUtil.clear();
        }
    }

    private void handle(MessageExt message) {
        String msgId = message.getMsgId();
        if (!idempotentRepository.tryAcquire(msgId, MqTopics.CACHE_EVICT, message.getKeys())) {
            return;
        }
        try {
            MqEvents.CacheEvictEvent event = objectMapper.readValue(message.getBody(), MqEvents.CacheEvictEvent.class);
            boolean ok = cacheClient.evict(event.cacheKey());
            if (!ok) {
                markTask(event.cacheKey(), CacheSyncTask.STATUS_FAILED, "删除缓存仍然失败");
                idempotentRepository.markFailed(msgId, "删除缓存失败");
                throw new RuntimeException("删除缓存失败: " + event.cacheKey());
            }
            if ("UPDATE".equals(event.reason())) {
                markTask(event.cacheKey(), CacheSyncTask.STATUS_SUCCESS, "");
            }
            log.info("缓存删除成功 key={}, reason={}", event.cacheKey(), event.reason());
            idempotentRepository.markSuccess(msgId);
        } catch (Exception e) {
            idempotentRepository.markFailed(msgId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 回写补偿任务状态。
     * 条件里放开 FAILED：否则一条失败过的任务，即使后面重试删除成功了，状态也会永远停在 FAILED。
     * 重试次数在这里累加（之前一直是 0，看不出到底重试了几次）。
     */
    private void markTask(String cacheKey, String status, String errorMsg) {
        CacheSyncTask update = new CacheSyncTask();
        update.setStatus(status);
        update.setErrorMsg(errorMsg);
        update.setUpdateTime(LocalDateTime.now());
        cacheSyncTaskMapper.update(null, Wrappers.<CacheSyncTask>lambdaUpdate()
                .set(CacheSyncTask::getStatus, status)
                .set(CacheSyncTask::getErrorMsg, errorMsg)
                .set(CacheSyncTask::getUpdateTime, LocalDateTime.now())
                .setSql("retry_count = retry_count + 1")
                .eq(CacheSyncTask::getCacheKey, cacheKey)
                .ne(CacheSyncTask::getStatus, CacheSyncTask.STATUS_SUCCESS));
    }
}