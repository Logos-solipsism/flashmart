package com.flashmart.modules.bench;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flashmart.common.cache.RedisCacheClient;
import com.flashmart.common.exception.BizException;
import com.flashmart.common.mq.MqEvents;
import com.flashmart.common.mq.MqProducer;
import com.flashmart.common.mq.MqTopics;
import com.flashmart.modules.activity.entity.FlashActivity;
import com.flashmart.modules.activity.mapper.FlashActivityMapper;
import com.flashmart.modules.activity.service.ActivityCacheKeys;
import com.flashmart.modules.order.entity.SeckillOrder;
import com.flashmart.modules.order.mapper.SeckillOrderMapper;
import com.flashmart.modules.seckill.service.OrderNoGenerator;
import com.flashmart.modules.seckill.service.SeckillStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 压测专用服务（只有 bench profile 才注册）。
 *
 * <p>为什么不直接用真实接口压：真实接口有"一人一单"和限流，同一个账号只能成功一次，
 * 没法把并发打满。所以这里提供两个<b>等价的对照接口</b>：
 * <ul>
 *   <li>seckillAsyncSynthetic：用合成 userId 走真实的「Lua 扣减 + MQ 异步下单」链路；</li>
 *   <li>seckillSync：用合成 userId 走「同步扣库存 + 同步写订单」，作为异步化之前的对照组。</li>
 * </ul>
 * 两个接口都跳过一人一单校验，其他逻辑与线上一致，所以耗时/吞吐的差异来自"是否异步"。
 * 压测前先调 reset 把库存、订单、缓存计数清干净，保证每轮条件一致。
 */
@Slf4j
@Service
@Profile("bench")
@RequiredArgsConstructor
public class BenchService {

    private final FlashActivityMapper activityMapper;
    private final SeckillOrderMapper orderMapper;
    private final SeckillStockService stockService;
    private final RedisCacheClient cacheClient;
    private final MqProducer mqProducer;

    /** 重置某个活动：库存（DB + Redis）、已购记录、缓存回源计数、该活动下的订单 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reset(Long activityId, int stock, boolean clearOrders) {
        FlashActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BizException("活动不存在");
        }
        activity.setTotalStock(stock);
        activity.setAvailableStock(stock);
        activity.setUpdateTime(LocalDateTime.now());
        activityMapper.updateById(activity);

        stockService.reset(activityId, stock, stock);
        cacheClient.evict(ActivityCacheKeys.detail(activityId));
        resetCacheLoadCount(activityId);

        int deleted = 0;
        if (clearOrders) {
            // 物理删除：逻辑删除的行仍会占用 unique(user_id, activity_id)，会让用户之后下不了单
            deleted = orderMapper.deleteByActivityPhysically(activityId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activityId", activityId);
        result.put("stock", stock);
        result.put("deletedOrders", deleted);
        return result;
    }

    /**
     * 异步版：Lua 扣减 + 发 MQ，立刻返回（线上抢购链路的等价实现）。
     * syntheticUserId 为空时随机生成一个高位用户 id —— 压测要模拟"很多人在抢"，
     * 每个请求都得是不同的人，否则会被"一人一单"挡住。
     */
    public Map<String, Object> seckillAsyncSynthetic(Long activityId, Long syntheticUserId) {
        if (syntheticUserId == null) {
            syntheticUserId = 9_000_000L + ThreadLocalRandom.current().nextInt(900_000_000);
        }
        FlashActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BizException("活动不存在");
        }
        stockService.ensurePrepared(activityId,
                () -> activityMapper.selectById(activityId).getAvailableStock(),
                activity.getTotalStock());
        // 压测接口按"不限购"处理（每个请求都是不同的合成用户），这里传 int 最大值是刻意的
        long remaining = stockService.trySeckill(activityId, syntheticUserId, Integer.MAX_VALUE);
        if (remaining == SeckillStockService.SOLD_OUT) {
            throw new BizException("已被抢光");
        }
        String orderNo = OrderNoGenerator.next(syntheticUserId);
        mqProducer.sendAsync(MqTopics.ORDER_CREATE, "order:" + orderNo,
                new MqEvents.OrderCreateEvent(orderNo, syntheticUserId, activityId,
                        activity.getProductId(), activity.getFlashPrice()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", orderNo);
        result.put("state", "QUEUING");
        return result;
    }

    /** 同步版（对照组）：扣数据库库存 + 写订单，全部在请求线程里做完，不发消息 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> seckillSync(Long activityId, Long syntheticUserId) {
        if (syntheticUserId == null) {
            syntheticUserId = 9_000_000L + ThreadLocalRandom.current().nextInt(900_000_000);
        }
        FlashActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BizException("活动不存在");
        }
        int affected = activityMapper.decreaseStock(activityId);
        if (affected == 0) {
            throw new BizException("已被抢光");
        }
        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(OrderNoGenerator.next(syntheticUserId));
        order.setUserId(syntheticUserId);
        order.setActivityId(activityId);
        order.setProductId(activity.getProductId());
        order.setFlashPrice(activity.getFlashPrice());
        order.setStatus(SeckillOrder.STATUS_PENDING_PAY);
        order.setVersion(0);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(order);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", order.getOrderNo());
        result.put("state", "PENDING_PAY");
        return result;
    }

    /** 活动当前的库存与订单统计：用来验证"没有超卖" */
    public Map<String, Object> stats(Long activityId) {
        FlashActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BizException("活动不存在");
        }
        List<SeckillOrder> orders = orderMapper.selectList(Wrappers.<SeckillOrder>lambdaQuery()
                .eq(SeckillOrder::getActivityId, activityId));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (SeckillOrder order : orders) {
            byStatus.merge(order.getStatus(), 1L, Long::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalStock", activity.getTotalStock());
        result.put("dbAvailableStock", activity.getAvailableStock());
        result.put("redisAvailableStock", stockService.currentStock(activityId, -1));
        result.put("orderCount", orders.size());
        result.put("ordersByStatus", byStatus);
        result.put("cacheLoadCount", cacheClient.loadCount(ActivityCacheKeys.detail(activityId)));
        return result;
    }

    /** 立即触发超时关单（把延迟消息按 1 秒投递），用于快速验证关单与库存回滚 */
    public Map<String, Object> forceTimeout(String orderNo) {
        mqProducer.sendAsync(MqTopics.ORDER_TIMEOUT, "order:" + orderNo,
                new MqEvents.OrderTimeoutEvent(orderNo), 1);
        return Map.of("orderNo", orderNo, "message", "已投递关单消息（延迟 1 秒）");
    }

    /** 查询/重置某个缓存 key 的回源次数（击穿、穿透实验用） */
    public Map<String, Object> cacheLoadCount(String key) {
        return Map.of("key", key, "loadCount", cacheClient.loadCount(key));
    }

    public void resetCacheLoadCount(Long activityId) {
        cacheClient.evict("cache:loadcount:" + ActivityCacheKeys.detail(activityId));
    }

    /** 重置任意 key 的回源计数（穿透实验会查不存在的活动） */
    public Map<String, Object> resetCacheLoadCount(String key) {
        cacheClient.evict("cache:loadcount:" + key);
        return Map.of("key", key, "loadCount", 0);
    }
}
