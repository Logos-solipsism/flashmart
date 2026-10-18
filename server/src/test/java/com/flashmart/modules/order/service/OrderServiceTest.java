package com.flashmart.modules.order.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.flashmart.common.context.UserContext;
import com.flashmart.common.exception.BizException;
import com.flashmart.modules.activity.entity.FlashActivity;
import com.flashmart.modules.activity.mapper.FlashActivityMapper;
import com.flashmart.modules.order.entity.SeckillOrder;
import com.flashmart.modules.order.exception.PriceChangedException;
import com.flashmart.modules.order.mapper.SeckillOrderMapper;
import com.flashmart.modules.product.mapper.ProductMapper;
import com.flashmart.modules.seckill.service.SeckillStockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单状态流转：支付幂等、越权拦截、乐观锁抢跑、超时关单只回滚库存。
 * 这几条是订单类系统最容易出线上事故的地方，全部用单元测试钉住。
 */
class OrderServiceTest {

    private final SeckillOrderMapper orderMapper = mock(SeckillOrderMapper.class);
    private final FlashActivityMapper activityMapper = mock(FlashActivityMapper.class);
    private final SeckillStockService stockService = mock(SeckillStockService.class);

    private OrderService newService() {
        OrderService service = new OrderService(orderMapper, activityMapper, mock(ProductMapper.class), stockService);
        ReflectionTestUtils.setField(service, "orderTimeoutSeconds", 30);
        return service;
    }

    private SeckillOrder order(String status, Long userId, Integer version) {
        SeckillOrder order = new SeckillOrder();
        order.setId(1L);
        order.setOrderNo("FM202610150000001");
        order.setUserId(userId);
        order.setActivityId(9001L);
        order.setProductId(9001L);
        order.setFlashPrice(39900);
        order.setStatus(status);
        order.setVersion(version);
        order.setCreateTime(LocalDateTime.now());
        return order;
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    private FlashActivity activity(Integer flashPrice) {
        FlashActivity activity = new FlashActivity();
        activity.setId(9001L);
        activity.setProductId(9001L);
        activity.setFlashPrice(flashPrice);
        activity.setTotalStock(100);
        activity.setAvailableStock(100);
        return activity;
    }

    @Test
    @DisplayName("下单时价格与数据库一致：正常成交（扣库存 + 插订单）")
    void createOrderWithSamePrice() {
        when(activityMapper.selectById(9001L)).thenReturn(activity(39900));
        when(activityMapper.decreaseStock(9001L)).thenReturn(1);

        SeckillOrder created = newService().createOrder("FM1", 9003L, 9001L, 9001L, 39900);

        org.junit.jupiter.api.Assertions.assertNotNull(created);
        assertEquals(39900, created.getFlashPrice());
        verify(orderMapper, times(1)).insert(org.mockito.ArgumentMatchers.any(SeckillOrder.class));
    }

    @Test
    @DisplayName("下单时价格已经被改过：拒单，且不动数据库库存、不插订单")
    void createOrderWithChangedPrice() {
        // 用户看到 39900，但数据库里已经被商家改成 49900
        when(activityMapper.selectById(9001L)).thenReturn(activity(49900));

        PriceChangedException error = assertThrows(PriceChangedException.class,
                () -> newService().createOrder("FM1", 9003L, 9001L, 9001L, 39900));

        assertEquals(39900, error.getSeenPrice());
        assertEquals(49900, error.getCurrentPrice());
        verify(activityMapper, never()).decreaseStock(org.mockito.ArgumentMatchers.any());
        verify(orderMapper, never()).insert(org.mockito.ArgumentMatchers.any(SeckillOrder.class));
    }

    @Test
    @DisplayName("活动不存在：拒单，不插订单")
    void createOrderWithMissingActivity() {
        when(activityMapper.selectById(9001L)).thenReturn(null);
        assertThrows(BizException.class, () -> newService().createOrder("FM1", 9003L, 9001L, 9001L, 39900));
        verify(orderMapper, never()).insert(org.mockito.ArgumentMatchers.any(SeckillOrder.class));
    }

    @Test
    @DisplayName("未登录不能支付")
    void payRequiresLogin() {
        BizException error = assertThrows(BizException.class, () -> newService().pay("FM1"));
        assertEquals(401, error.getCode());
    }

    @Test
    @DisplayName("不能支付别人的订单")
    void payOthersOrderRejected() {
        UserContext.set(new UserContext.LoginUser(9003L, "BUYER", "127.0.0.1"));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order(SeckillOrder.STATUS_PENDING_PAY, 9004L, 0));
        BizException error = assertThrows(BizException.class, () -> newService().pay("FM1"));
        assertEquals(403, error.getCode());
    }

    @Test
    @DisplayName("已支付的订单重复支付是幂等的")
    void payIsIdempotent() {
        UserContext.set(new UserContext.LoginUser(9003L, "BUYER", "127.0.0.1"));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order(SeckillOrder.STATUS_PAID, 9003L, 1));
        Map<String, Object> result = newService().pay("FM1");
        assertEquals(SeckillOrder.STATUS_PAID, result.get("status"));
        verify(orderMapper, never()).payWithVersion(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("已关闭的订单不能支付")
    void payClosedOrderRejected() {
        UserContext.set(new UserContext.LoginUser(9003L, "BUYER", "127.0.0.1"));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order(SeckillOrder.STATUS_TIMEOUT, 9003L, 1));
        BizException error = assertThrows(BizException.class, () -> newService().pay("FM1"));
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("已关闭"));
    }

    @Test
    @DisplayName("乐观锁抢跑（更新 0 行）时提示状态已变化")
    void payOptimisticLockConflict() {
        UserContext.set(new UserContext.LoginUser(9003L, "BUYER", "127.0.0.1"));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order(SeckillOrder.STATUS_PENDING_PAY, 9003L, 3));
        when(orderMapper.payWithVersion(eq("FM202610150000001"), anyString(), eq(3))).thenReturn(0);
        BizException error = assertThrows(BizException.class, () -> newService().pay("FM202610150000001"));
        assertTrue(error.getMessage().contains("状态已变化"));
    }

    @Test
    @DisplayName("超时关单成功后回滚库存，但不把「机会」还给用户")
    void timeoutRollsBackStockOnly() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order(SeckillOrder.STATUS_PENDING_PAY, 9003L, 0));
        when(orderMapper.timeoutIfPending("FM202610150000001")).thenReturn(1);
        assertTrue(newService().timeoutOrder("FM202610150000001"));
        verify(activityMapper).increaseStock(9001L);
        verify(stockService).rollbackStock(9001L);
        verify(stockService, never()).rollbackAll(any(), any());
    }

    @Test
    @DisplayName("已支付的订单不会被超时关单，也不会回滚库存")
    void timeoutSkippedWhenPaid() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order(SeckillOrder.STATUS_PAID, 9003L, 1));
        when(orderMapper.timeoutIfPending("FM202610150000001")).thenReturn(0);
        assertFalse(newService().timeoutOrder("FM202610150000001"));
        verify(activityMapper, never()).increaseStock(any());
        verify(stockService, never()).rollbackStock(any());
    }
}
