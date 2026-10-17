package com.flashmart.modules.seckill.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.flashmart.common.context.UserContext;
import com.flashmart.common.exception.BizException;
import com.flashmart.common.mq.MqProducer;
import com.flashmart.modules.activity.service.ActivityService;
import com.flashmart.modules.order.entity.SeckillOrder;
import com.flashmart.modules.order.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 抢购结果查询的四种状态。
 *
 * <p>这一组是"用户体验"的最后一道关口：如果订单没能生成，
 * 必须让前端明确知道"失败了、名额已退回、可以再抢"，
 * 而不是让用户对着"排队中"干等到超时。
 */
class SeckillServiceResultTest {

    private final ActivityService activityService = mock(ActivityService.class);
    private final SeckillStockService stockService = mock(SeckillStockService.class);
    private final OrderService orderService = mock(OrderService.class);
    private final OrderStateNotifier stateNotifier = mock(OrderStateNotifier.class);
    private final MqProducer mqProducer = mock(MqProducer.class);

    private SeckillService service() {
        return new SeckillService(activityService, stockService, orderService, stateNotifier, mqProducer);
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    private SeckillOrder order(String status, Long userId) {
        SeckillOrder order = new SeckillOrder();
        order.setOrderNo("FM1");
        order.setUserId(userId);
        order.setActivityId(9001L);
        order.setProductId(9001L);
        order.setFlashPrice(39900);
        order.setStatus(status);
        order.setVersion(0);
        order.setCreateTime(LocalDateTime.now());
        return order;
    }

    @Test
    @DisplayName("未登录不能查结果")
    void requiresLogin() {
        BizException error = assertThrows(BizException.class, () -> service().result("FM1"));
        assertEquals(401, error.getCode());
    }

    @Test
    @DisplayName("订单还没落库、也没有过程标记：返回 QUEUING")
    void queuing() {
        UserContext.set(new UserContext.LoginUser(9003L, "BUYER", "127.0.0.1"));
        when(orderService.findByOrderNo("FM1")).thenReturn(null);
        Map<String, Object> result = service().result("FM1");
        assertEquals("QUEUING", result.get("state"));
    }

    @Test
    @DisplayName("有失败标记：返回 FAILED，并告诉前端可以再抢（不能只给排队中）")
    void failed() {
        UserContext.set(new UserContext.LoginUser(9003L, "BUYER", "127.0.0.1"));
        when(orderService.findByOrderNo("FM1")).thenReturn(null);
        when(stateNotifier.failedReason("FM1")).thenReturn("本次未能生成订单，名额已退回，可稍后再试");

        Map<String, Object> result = service().result("FM1");

        assertEquals("FAILED", result.get("state"));
        assertEquals(true, result.get("retryable"));
        assertTrue(String.valueOf(result.get("message")).contains("名额已退回"));
    }

    @Test
    @DisplayName("有重试中标记：返回 RETRYING（中间态，不是失败）")
    void retrying() {
        UserContext.set(new UserContext.LoginUser(9003L, "BUYER", "127.0.0.1"));
        when(orderService.findByOrderNo("FM1")).thenReturn(null);
        when(stateNotifier.failedReason("FM1")).thenReturn(null);
        when(stateNotifier.retryingReason("FM1")).thenReturn("订单生成遇到波动，正在重试");

        Map<String, Object> result = service().result("FM1");

        assertEquals("RETRYING", result.get("state"));
        assertEquals(null, result.get("retryable"));
    }

    @Test
    @DisplayName("订单已落库：返回真实状态，并清掉过程标记")
    void orderExists() {
        UserContext.set(new UserContext.LoginUser(9003L, "BUYER", "127.0.0.1"));
        when(orderService.findByOrderNo("FM1")).thenReturn(order(SeckillOrder.STATUS_PENDING_PAY, 9003L));
        when(orderService.toVO(org.mockito.ArgumentMatchers.any(SeckillOrder.class)))
                .thenReturn(Map.of("orderNo", "FM1", "status", "PENDING_PAY"));

        Map<String, Object> result = service().result("FM1");

        assertEquals("PENDING_PAY", result.get("state"));
        verify(stateNotifier).markSucceeded("FM1");
    }

    @Test
    @DisplayName("不能查别人的订单")
    void othersOrderRejected() {
        UserContext.set(new UserContext.LoginUser(9003L, "BUYER", "127.0.0.1"));
        when(orderService.findByOrderNo("FM1")).thenReturn(order(SeckillOrder.STATUS_PENDING_PAY, 9004L));
        BizException error = assertThrows(BizException.class, () -> service().result("FM1"));
        assertEquals(403, error.getCode());
        verify(stateNotifier, never()).markSucceeded("FM1");
    }
}
