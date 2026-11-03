package com.flashmart.modules.agent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.flashmart.common.exception.BizException;
import com.flashmart.modules.activity.entity.FlashActivity;
import com.flashmart.modules.activity.mapper.FlashActivityMapper;
import com.flashmart.modules.activity.service.ActivityService;
import com.flashmart.modules.agent.vo.AgentActivityVO;
import com.flashmart.modules.agent.vo.AgentOrderVO;
import com.flashmart.modules.order.entity.SeckillOrder;
import com.flashmart.modules.order.mapper.SeckillOrderMapper;
import com.flashmart.modules.product.entity.Product;
import com.flashmart.modules.product.mapper.ProductMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 只读查询：字段映射、limit 归一化、两个过滤条件取交集、以及「绝不写库」。
 *
 * <p>最后一条是这组接口的立身之本 —— 外部服务拿到的令牌应该只能看，不能改，
 * 所以这里除了看返回值，还要确认订单表上没有任何写调用。
 */
class AgentQueryServiceTest {

    private final ActivityService activityService = mock(ActivityService.class);
    private final FlashActivityMapper activityMapper = mock(FlashActivityMapper.class);
    private final SeckillOrderMapper orderMapper = mock(SeckillOrderMapper.class);
    private final ProductMapper productMapper = mock(ProductMapper.class);

    private final AgentQueryService service =
            new AgentQueryService(activityService, activityMapper, orderMapper, productMapper);

    private Map<String, Object> activityItem(long id, int remaining) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("title", "活动-" + id);
        item.put("productTitle", "商品-" + id);
        item.put("flashPrice", 39900);
        item.put("originPrice", 59900);
        item.put("totalStock", 100);
        item.put("remainingStock", remaining);
        item.put("startTime", "2026-01-01T10:00:00");
        item.put("endTime", "2026-01-01T12:00:00");
        item.put("status", 1);
        return item;
    }

    private SeckillOrder order(String orderNo, Long userId, Long activityId, Long productId) {
        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setActivityId(activityId);
        order.setProductId(productId);
        order.setFlashPrice(39900);
        order.setStatus(SeckillOrder.STATUS_PENDING_PAY);
        order.setVersion(0);
        order.setCreateTime(LocalDateTime.of(2026, 1, 1, 10, 0, 0));
        return order;
    }

    @Test
    @DisplayName("活动列表：复用买家侧查询，并按契约映射出 id/标题/价格/剩余库存/起止时间")
    void activitiesAreMappedFromBuyerList() {
        when(activityService.listOnline("秒杀")).thenReturn(List.of(activityItem(11L, 7)));

        List<AgentActivityVO> result = service.activities("秒杀", 20);

        assertEquals(1, result.size());
        AgentActivityVO vo = result.get(0);
        assertEquals(11L, vo.id());
        assertEquals("活动-11", vo.title());
        assertEquals(39900, vo.flashPrice());
        assertEquals(7, vo.remainingStock());
        assertEquals("2026-01-01T10:00:00", vo.startTime());
        assertEquals("2026-01-01T12:00:00", vo.endTime());
        verify(activityService).listOnline("秒杀");
    }

    @Test
    @DisplayName("活动列表：limit 生效，超过上限按上限截断")
    void activityLimitIsCapped() {
        when(activityService.listOnline(null)).thenReturn(List.of(
                activityItem(1L, 1), activityItem(2L, 2), activityItem(3L, 3)));

        assertEquals(2, service.activities(null, 2).size());
        // 传 0/负数视为「没传」，用默认值，而不是变成「不限」
        assertEquals(3, service.activities(null, 0).size());
        assertEquals(AgentQueryService.normalizeLimit(0), 20);
        assertEquals(AgentQueryService.normalizeLimit(999), 100);
    }

    @Test
    @DisplayName("订单查询：按 userId 查，活动与商品标题从关联表补齐")
    void ordersByUserId() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order("FM1", 9003L, 11L, 22L)));
        FlashActivity activity = new FlashActivity();
        activity.setId(11L);
        activity.setTitle("活动-11");
        Product product = new Product();
        product.setId(22L);
        product.setTitle("商品-22");
        when(activityMapper.selectBatchIds(any())).thenReturn(List.of(activity));
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(product));

        List<AgentOrderVO> result = service.orders(9003L, null);

        assertEquals(1, result.size());
        AgentOrderVO vo = result.get(0);
        assertEquals("FM1", vo.orderNo());
        assertEquals(9003L, vo.userId());
        assertEquals("活动-11", vo.activityTitle());
        assertEquals("商品-22", vo.productTitle());
        assertEquals(SeckillOrder.STATUS_PENDING_PAY, vo.status());
        assertEquals("2026-01-01T10:00:00", vo.createTime());
    }

    @Test
    @DisplayName("订单查询：只给 orderNo 也能查")
    void ordersByOrderNo() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order("FM1", 9003L, 11L, 22L)));

        List<AgentOrderVO> result = service.orders(null, "FM1");

        assertEquals(1, result.size());
        assertEquals("FM1", result.get(0).orderNo());
    }

    @Test
    @DisplayName("订单查询：userId 与 orderNo 都不给 -> 拒绝，不做全表扫描")
    void ordersWithoutAnyFilterAreRejected() {
        assertThrows(BizException.class, () -> service.orders(null, "  "));
        verify(orderMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("订单查询：查不到返回空列表，不是 null")
    void ordersReturnEmptyListWhenNothingFound() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.orders(404L, null).isEmpty());
    }

    @Test
    @DisplayName("只读契约：整条查询链路在订单表与活动表上没有任何写调用")
    void readOnlyNeverWrites() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order("FM1", 9003L, 11L, 22L)));
        when(activityService.listOnline(any())).thenReturn(List.of(activityItem(11L, 7)));

        service.orders(9003L, null);
        service.activities(null, 20);

        verify(orderMapper, never()).insert(any());
        verify(orderMapper, never()).update(any(), any());
        verify(orderMapper, never()).delete(any());
        verify(orderMapper, never()).updateById(any());
        verify(orderMapper, never()).deleteById(anyLong());
        verify(activityMapper, never()).insert(any());
        verify(activityMapper, never()).update(any(), any());
        verify(activityMapper, never()).deleteById(anyLong());
        verify(activityMapper, never()).increaseStock(anyLong());
        verify(activityMapper, never()).decreaseStock(anyLong());
    }
}
