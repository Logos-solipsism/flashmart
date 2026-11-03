package com.flashmart.modules.order.service;

import com.flashmart.modules.order.entity.SeckillOrder;
import com.flashmart.modules.order.mapper.SeckillOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 兜底关单扫描：它是"延迟消息丢了"的最后一道网，所以要求
 * ① 没有超时订单时一次关单都不调；② 单条失败不能拖住同批的其它订单。
 */
class OrderTimeoutFallbackScannerTest {

    private final SeckillOrderMapper orderMapper = mock(SeckillOrderMapper.class);
    private final OrderService orderService = mock(OrderService.class);
    private final OrderTimeoutFallbackScanner scanner = new OrderTimeoutFallbackScanner(orderMapper, orderService);

    private SeckillOrder order(String orderNo) {
        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(orderNo);
        return order;
    }

    @Test
    @DisplayName("没有超时未支付订单时：不调用关单")
    void idleWhenNothingStale() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        scanner.scan();

        verify(orderService, never()).timeoutOrder(any());
    }

    @Test
    @DisplayName("同批里一条关单失败，不影响其它订单继续兜底")
    void oneFailureDoesNotBlockOthers() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order("A"), order("B")));
        when(orderService.timeoutOrder("A")).thenThrow(new RuntimeException("数据库抖动"));
        when(orderService.timeoutOrder("B")).thenReturn(true);

        scanner.scan();

        verify(orderService).timeoutOrder("A");
        verify(orderService).timeoutOrder("B");
    }
}
