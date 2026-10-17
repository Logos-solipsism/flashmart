package com.flashmart.modules.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashmart.common.mq.MqEvents;
import com.flashmart.common.mq.MqIdempotentRepository;
import com.flashmart.common.mq.MqProducer;
import com.flashmart.common.mq.MqTopics;
import com.flashmart.common.trace.TraceIdFilter;
import com.flashmart.common.trace.TraceUtil;
import com.flashmart.modules.order.entity.SeckillOrder;
import com.flashmart.modules.order.service.OrderService;
import com.flashmart.modules.seckill.service.OrderStateNotifier;
import com.flashmart.modules.seckill.service.SeckillStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 抢购下单消费者：抢购接口的并发压力到这里被"摊平"成顺序的数据库写入。
 *
 * <p>可靠性：幂等表拦截重复消息 → 写订单（唯一索引兜底一人一单）→ 扣数据库库存 →
 * 投递延迟消息做超时关单；任一步失败都抛异常交给 RocketMQ 阶梯重试，超限进死信。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.ORDER_CREATE, consumerGroup = MqTopics.CG_ORDER_CREATE,
        consumeThreadMax = 32)
public class OrderCreateConsumer implements RocketMQListener<MessageExt> {

    private final ObjectMapper objectMapper;
    private final MqIdempotentRepository idempotentRepository;
    private final OrderService orderService;
    private final SeckillStockService stockService;
    private final OrderStateNotifier stateNotifier;
    private final MqProducer mqProducer;
    private final StringRedisTemplate redis;

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
        if (!idempotentRepository.tryAcquire(msgId, MqTopics.ORDER_CREATE, message.getKeys())) {
            return;
        }
        MqEvents.OrderCreateEvent event;
        try {
            event = objectMapper.readValue(message.getBody(), MqEvents.OrderCreateEvent.class);
        } catch (Exception e) {
            idempotentRepository.markFailed(msgId, e.getMessage());
            throw new RuntimeException("消息解析失败", e);
        }
        try {
            SeckillOrder order = orderService.createOrder(event.orderNo(), event.userId(),
                    event.activityId(), event.productId(), event.flashPrice());
            if (order == null) {
                // 重复下单：唯一索引已拦下，Redis 侧把这次预扣的库存与机会都还回去。
                // 这是"确定失败"：要留痕，否则用户拿 orderNo 轮询只会一直看到"排队中"，干等到超时。
                rollbackOnce(msgId, event);
                stateNotifier.markFailed(event.orderNo(), "本次未能生成订单（你已抢到过该活动），名额已退回，可稍后再试");
                idempotentRepository.markSuccess(msgId);
                return;
            }
            // 投递延迟消息：到点自动关单并回滚库存（不用定时任务扫全表）
            mqProducer.sendAsync(MqTopics.ORDER_TIMEOUT, "order:" + event.orderNo(),
                    new MqEvents.OrderTimeoutEvent(event.orderNo()),
                    MqProducer.nearestDelayLevel(orderService.timeoutSeconds()));
            idempotentRepository.markSuccess(msgId);
            // 订单落库成功，清掉过程中的"重试中/失败"标记
            stateNotifier.markSucceeded(event.orderNo());
            log.info("订单创建成功 orderNo={}, userId={}, activityId={}",
                    event.orderNo(), event.userId(), event.activityId());
        } catch (Exception e) {
            // 数据库库存扣不动等异常：回滚 Redis 预扣的库存与机会，再抛出去让 MQ 重试 / 进死信
            rollbackOnce(msgId, event);
            idempotentRepository.markFailed(msgId, e.getMessage());
            // 留痕区分"还会重试"和"已经放弃"：RocketMQ 重试到第 3 次以后，就让前端明确告诉用户失败
            if (message.getReconsumeTimes() >= 2) {
                stateNotifier.markFailed(event.orderNo(), "本次未能生成订单，名额已退回，可稍后再试");
            } else {
                stateNotifier.markRetrying(event.orderNo(), "订单生成遇到波动，正在重试");
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * 回滚 Redis 预扣的库存与机会，且**每条消息只回滚一次**。
     *
     * <p>为什么必须做这个保护：MQ 会重试失败的消息，而回滚用的是无上限的 Redis INCR，
     * 一旦同一条消息被重试多次，库存就会被反复加回去（凭空多出库存，比超卖更难查）。
     * 这里用一个 SETNX 标记把"每条消息只回滚一次"钉死。
     */
    private void rollbackOnce(String msgId, MqEvents.OrderCreateEvent event) {
        Boolean first = redis.opsForValue().setIfAbsent("seckill:rollback:" + msgId, "1", Duration.ofHours(2));
        if (!Boolean.TRUE.equals(first)) {
            log.warn("该消息已经回滚过，跳过重复回滚 msgId={}", msgId);
            return;
        }
        stockService.rollbackAll(event.activityId(), event.userId());
    }
}