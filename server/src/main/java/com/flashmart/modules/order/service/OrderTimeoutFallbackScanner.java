package com.flashmart.modules.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flashmart.modules.order.entity.SeckillOrder;
import com.flashmart.modules.order.mapper.SeckillOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时关单的兜底扫描。
 *
 * <p>关单的主机制是 RocketMQ 延迟消息（下单成功的那个消费者顺手投一条到点关单的消息）。
 * 但延迟消息本身会丢——发送失败、broker 抖动等——一旦丢了，订单会永远停在 PENDING_PAY：
 * 用户不付款，库存也不回池子，表现为"少卖"。这个任务只负责兜住漏网的那几条，
 * 不是主机制，所以跑得很低频。
 *
 * <p>幂等：关单走 {@link SeckillOrderMapper#timeoutIfPending} 的条件更新（只允许 PENDING_PAY → TIMEOUT），
 * 只有真正改动了那一行才会回滚库存，所以延迟消息与兜底扫描同时命中时不会重复回滚。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutFallbackScanner {

    private static final int BATCH = 200;

    /** 关单时间（秒），与延迟消息共用同一个配置项 */
    @Value("${flashmart.seckill.order-timeout-seconds:30}")
    private long orderTimeoutSeconds;

    /** 宽限（秒）：比超时时间再多等这么久再兜底，避免和延迟消息抢同一条订单 */
    @Value("${flashmart.seckill.timeout-fallback-grace-seconds:10}")
    private long graceSeconds;

    private final SeckillOrderMapper orderMapper;
    private final OrderService orderService;

    @Scheduled(fixedDelayString = "${flashmart.seckill.timeout-fallback-interval-ms:60000}")
    public void scan() {
        try {
            LocalDateTime deadline = LocalDateTime.now().minusSeconds(orderTimeoutSeconds + graceSeconds);
            List<SeckillOrder> stale = orderMapper.selectList(Wrappers.<SeckillOrder>lambdaQuery()
                    .eq(SeckillOrder::getStatus, SeckillOrder.STATUS_PENDING_PAY)
                    .lt(SeckillOrder::getCreateTime, deadline)
                    .last("limit " + BATCH));
            if (stale.isEmpty()) {
                return;
            }
            int closed = 0;
            for (SeckillOrder order : stale) {
                // 单条失败不影响其它订单：这一条下一轮还会被扫到
                try {
                    if (orderService.timeoutOrder(order.getOrderNo())) {
                        closed++;
                    }
                } catch (Exception e) {
                    log.warn("兜底关单失败，下一轮重试 orderNo={}", order.getOrderNo(), e);
                }
            }
            log.warn("兜底关单扫描：扫到 {} 条超时未支付订单，补关 {} 条（这些是延迟消息漏掉的）", stale.size(), closed);
        } catch (Exception e) {
            // 扫描本身出问题不能影响业务，下一轮再来
            log.error("兜底关单扫描异常", e);
        }
    }
}
