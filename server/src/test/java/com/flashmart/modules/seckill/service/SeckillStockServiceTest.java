package com.flashmart.modules.seckill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存覆盖的两种语义必须分清：
 * 商家改库存只能覆盖数字，**绝不能清已购记录**（否则一人一单被绕过）；
 * 压测清场才连已购记录一起清。
 */
class SeckillStockServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    private SeckillStockService service() {
        when(redis.opsForValue()).thenReturn(ops);
        return new SeckillStockService(redis);
    }

    @Test
    @DisplayName("商家改库存：覆盖库存与总库存上限，但绝不动已购记录")
    void resetStockKeepsBoughtRecords() {
        service().resetStock(9001L, 17, 80);

        verify(ops).set(eq("seckill:stock:9001"), eq("17"), any(Duration.class));
        verify(ops).set(eq("seckill:total:9001"), eq("80"), any(Duration.class));
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("压测清场：连已购记录一起清，让同一批用户能重新抢")
    void resetClearsBoughtRecords() {
        service().reset(9001L, 100, 100);

        verify(redis, times(1)).delete("seckill:bought:9001");
    }
}
