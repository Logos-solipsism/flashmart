package com.flashmart.modules.cache.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.flashmart.common.cache.RedisCacheClient;
import com.flashmart.common.mq.MqEvents;
import com.flashmart.common.mq.MqProducer;
import com.flashmart.common.mq.MqTopics;
import com.flashmart.modules.cache.entity.CacheSyncTask;
import com.flashmart.modules.cache.mapper.CacheSyncTaskMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存一致性：删缓存成功要投一条延迟消息做双删；删缓存失败要落补偿任务并投 MQ 重试。
 * 这三条是 Cache Aside 能兜住一致性的关键，用测试钉住。
 */
class CacheEvictServiceTest {

    private final RedisCacheClient cacheClient = mock(RedisCacheClient.class);
    private final CacheSyncTaskMapper taskMapper = mock(CacheSyncTaskMapper.class);
    private final MqProducer mqProducer = mock(MqProducer.class);

    private CacheEvictService newService() {
        CacheEvictService service = new CacheEvictService(cacheClient, taskMapper, mqProducer);
        ReflectionTestUtils.setField(service, "doubleDeleteDelaySeconds", 5);
        return service;
    }

    @Test
    @DisplayName("删缓存成功：不落补偿任务，但要投延迟双删消息")
    void successOnlySendsDoubleDelete() {
        when(cacheClient.evict("activity:detail:9001")).thenReturn(true);
        newService().evictWithCompensation("activity:detail:9001");

        verify(taskMapper, never()).insert(any(CacheSyncTask.class));
        verify(mqProducer, times(1)).sendAsync(eq(MqTopics.CACHE_EVICT), anyString(),
                any(MqEvents.CacheEvictEvent.class), anyInt());
    }

    @Test
    @DisplayName("删缓存失败：落一条 PENDING 补偿任务，并投 MQ 让消费者重试")
    void failureWritesCompensationTask() {
        when(cacheClient.evict("activity:detail:9001")).thenReturn(false);
        newService().evictWithCompensation("activity:detail:9001");

        verify(taskMapper, times(1)).insert(any(CacheSyncTask.class));
        // 失败补偿 1 条 + 延迟双删 1 条
        verify(mqProducer, times(2)).sendAsync(eq(MqTopics.CACHE_EVICT), anyString(),
                any(MqEvents.CacheEvictEvent.class), anyInt());
    }

    @Test
    @DisplayName("补偿任务的初始状态是 PENDING、重试次数从 0 开始")
    void compensationTaskInitialState() {
        when(cacheClient.evict(anyString())).thenReturn(false);
        newService().evict("activity:detail:9001");

        org.mockito.ArgumentCaptor<CacheSyncTask> captor = org.mockito.ArgumentCaptor.forClass(CacheSyncTask.class);
        verify(taskMapper).insert(captor.capture());
        CacheSyncTask task = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(CacheSyncTask.STATUS_PENDING, task.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(0, task.getRetryCount());
        org.junit.jupiter.api.Assertions.assertEquals("activity:detail:9001", task.getCacheKey());
    }
}
