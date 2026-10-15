package com.flashmart.modules.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashmart.common.mq.MqEvents;
import com.flashmart.common.mq.MqIdempotentRepository;
import com.flashmart.common.mq.MqTopics;
import com.flashmart.common.trace.TraceIdFilter;
import com.flashmart.common.trace.TraceUtil;
import com.flashmart.modules.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 超时关单消费者：消费延迟消息，把到点仍未支付的订单关掉并回滚库存。
 * 关单本身也走乐观锁（只允许从 PENDING_PAY 出发），所以不会误关已支付的订单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.ORDER_TIMEOUT, consumerGroup = MqTopics.CG_ORDER_TIMEOUT,
        consumeThreadMax = 32)
public class OrderTimeoutConsumer implements RocketMQListener<MessageExt> {

    private final ObjectMapper objectMapper;
    private final MqIdempotentRepository idempotentRepository;
    private final OrderService orderService;

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
        if (!idempotentRepository.tryAcquire(msgId, MqTopics.ORDER_TIMEOUT, message.getKeys())) {
            return;
        }
        try {
            MqEvents.OrderTimeoutEvent event = objectMapper.readValue(message.getBody(), MqEvents.OrderTimeoutEvent.class);
            orderService.timeoutOrder(event.orderNo());
            idempotentRepository.markSuccess(msgId);
        } catch (Exception e) {
            idempotentRepository.markFailed(msgId, e.getMessage());
            throw new RuntimeException(e);
        }
    }
}