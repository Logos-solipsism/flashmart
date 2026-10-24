package com.flashmart.common.blacklist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IP 黑名单服务：计数与封禁的边界（未达阈值 / 达到阈值 / 首次违规设窗口过期）、
 * 以及"Redis 挂了必须放行、写失败也不能抛"这两条 fail-open 铁律。
 */
class BlacklistServiceTest {

    private static final String IP = "203.0.113.7";
    private static final String BAN_KEY = "black:list:" + IP;
    private static final String VIOLATION_KEY = "black:violation:" + IP;

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final BlacklistService service = new BlacklistService(redis);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        // BlacklistService 的四个策略值靠 @Value 注入；单测不走 Spring，直接反射塞进去
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "violationThreshold", 3);
        ReflectionTestUtils.setField(service, "violationWindowSeconds", 60L);
        ReflectionTestUtils.setField(service, "banSeconds", 300L);
    }

    @Test
    @DisplayName("recordViolation：未达阈值不封禁，且只在计数为 1 时设置违规窗口过期")
    void recordViolationBelowThresholdDoesNotBan() {
        when(valueOps.increment(VIOLATION_KEY)).thenReturn(1L, 2L);

        service.recordViolation(IP);
        service.recordViolation(IP);

        verify(redis, never()).hasKey(anyString());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        // 第二次违规（计数为 2）不再续期窗口：否则窗口会变成永不衰减的固定窗口
        verify(redis).expire(VIOLATION_KEY, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("recordViolation：达到阈值即封禁（带 TTL）并清掉违规计数")
    void recordViolationAtThresholdBans() {
        when(valueOps.increment(VIOLATION_KEY)).thenReturn(3L);

        service.recordViolation(IP);

        verify(valueOps).set(BAN_KEY, "1", Duration.ofSeconds(300));
        verify(redis).delete(VIOLATION_KEY);
    }

    @Test
    @DisplayName("recordViolation：无效 IP 直接返回，绝不碰 Redis")
    void recordViolationIgnoresInvalidIp() {
        service.recordViolation(null);
        service.recordViolation("   ");
        service.recordViolation(ClientIpResolver.UNKNOWN_IP);

        verify(redis, never()).opsForValue();
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("recordViolation：Redis 写失败必须被吞掉，不能把限流路径带崩")
    void recordViolationSwallowsRedisFailure() {
        when(valueOps.increment(VIOLATION_KEY)).thenThrow(new RuntimeException("redis down"));

        service.recordViolation(IP);

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("isBanned：key 存在 = 已封禁；key 不存在 = 未封禁")
    void isBannedReadsRedisKey() {
        when(redis.hasKey(BAN_KEY)).thenReturn(true);
        assertTrue(service.isBanned(IP));

        when(redis.hasKey(BAN_KEY)).thenReturn(false);
        assertFalse(service.isBanned(IP));
    }

    @Test
    @DisplayName("isBanned：null / 空白 / unknown-ip 一律 false，不查 Redis")
    void isBannedRejectsInvalidIp() {
        assertFalse(service.isBanned(null));
        assertFalse(service.isBanned("  "));
        assertFalse(service.isBanned(ClientIpResolver.UNKNOWN_IP));

        verify(redis, never()).hasKey(anyString());
    }

    @Test
    @DisplayName("isBanned：查询抛异常时 fail-open（放行）")
    void isBannedFailOpenOnRedisError() {
        when(redis.hasKey(BAN_KEY)).thenThrow(new RuntimeException("redis down"));

        assertFalse(service.isBanned(IP));
    }

    @Test
    @DisplayName("reset：同时删除封禁 key 与违规计数 key")
    void resetDeletesBothKeys() {
        service.reset(IP);

        verify(redis).delete(List.of(BAN_KEY, VIOLATION_KEY));
    }

    @Test
    @DisplayName("rejectedCount：由过滤器计数、可清零（压测取证用）")
    void rejectedCountIsInMemoryCounter() {
        assertEquals(0, service.rejectedCount());

        service.recordRejected();
        service.recordRejected();

        assertEquals(2, service.rejectedCount());
        service.resetRejectedCount();
        assertEquals(0, service.rejectedCount());
    }

    @Test
    @DisplayName("总开关关闭：isBanned 直接 false，完全不查 Redis")
    void isBannedSkipsRedisWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertFalse(service.isBanned(IP));

        verify(redis, never()).hasKey(anyString());
    }

    @Test
    @DisplayName("总开关关闭：recordViolation 完全不写 Redis（禁用期不累计违规）")
    void recordViolationSkipsRedisWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.recordViolation(IP);

        verify(valueOps, never()).increment(anyString());
        verify(redis, never()).expire(anyString(), any(Duration.class));
        verify(redis, never()).delete(anyString());
    }
}
