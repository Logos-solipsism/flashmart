package com.flashmart.common.mq;

import com.flashmart.common.trace.TraceUtil;
import com.flashmart.common.trace.TraceIdFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 消息发送封装。
 * 两个细节：
 * ① 把 traceId 放进消息属性，消费端拿出来继续用 → 一次请求跨 HTTP / MQ / 消费端的日志能串起来；
 * ② 抢购链路用异步发送（sendAsync），主链路不等 broker 回执，只有发送失败才降级记录日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqProducer {

    private final RocketMQTemplate rocketMQTemplate;

    /** 延迟消息的等级（RocketMQ 只支持固定档位：1s 5s 10s 30s 1m …） */
    private static final int[] DELAY_LEVEL_SECONDS = {
            1, 5, 10, 30, 60, 120, 180, 240, 300, 360, 420, 480, 540, 600, 1200, 1800, 3600, 7200};

    public void sendAsync(String topic, String bizKey, Object payload) {
        sendAsync(topic, bizKey, payload, 0);
    }

    public void sendAsync(String topic, String bizKey, Object payload, int delayLevel) {
        var message = MessageBuilder.withPayload(payload)
                .setHeader(org.apache.rocketmq.spring.support.RocketMQHeaders.KEYS, bizKey)
                .setHeader(TraceUtil.HEADER, TraceUtil.current())
                .build();
        try {
            if (delayLevel > 0) {
                rocketMQTemplate.asyncSend(topic, message, new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        log.debug("延迟消息发送成功 topic={}, key={}, level={}", topic, bizKey, delayLevel);
                    }

                    @Override
                    public void onException(Throwable e) {
                        log.error("延迟消息异步发送失败，改为同步重发一次 topic={}, key={}", topic, bizKey, e);
                        syncResend(topic, payload, bizKey, delayLevel);
                    }
                }, 3000, delayLevel);
            } else {
                rocketMQTemplate.asyncSend(topic, message, new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        log.debug("消息发送成功 topic={}, key={}", topic, bizKey);
                    }

                    @Override
                    public void onException(Throwable e) {
                        // 异步发送失败必须兜底：否则用户看到"抢到了"，消息却没进 broker，订单永远不会落库
                        log.error("消息异步发送失败，改为同步重发一次 topic={}, key={}", topic, bizKey, e);
                        syncResend(topic, payload, bizKey, 0);
                    }
                });
            }
        } catch (Exception e) {
            log.error("消息发送异常 topic={}, key={}", topic, bizKey, e);
        }
    }

    /**
     * 异步发送失败后的兜底：同步再发一次（带 broker 自带的失败重试）。
     * 仍然失败就记 ERROR —— 这一层不引入本地消息表，属于明确记录的边界。
     */
    private void syncResend(String topic, Object payload, String bizKey, int delayLevel) {
        try {
            var message = MessageBuilder.withPayload(payload)
                    .setHeader(org.apache.rocketmq.spring.support.RocketMQHeaders.KEYS, bizKey)
                    .setHeader(TraceUtil.HEADER, TraceUtil.current())
                    .build();
            if (delayLevel > 0) {
                rocketMQTemplate.syncSend(topic, message, 3000, delayLevel);
            } else {
                rocketMQTemplate.syncSend(topic, message, 3000);
            }
            log.warn("同步重发成功 topic={}, key={}", topic, bizKey);
        } catch (Exception ex) {
            log.error("同步重发仍然失败，消息可能丢失 topic={}, key={}", topic, bizKey, ex);
        }
    }

    /** 按秒数选最接近的延迟档位（RocketMQ 不支持任意秒数的延迟） */
    public static int nearestDelayLevel(int seconds) {
        for (int i = 0; i < DELAY_LEVEL_SECONDS.length; i++) {
            if (DELAY_LEVEL_SECONDS[i] >= seconds) {
                return i + 1;
            }
        }
        return DELAY_LEVEL_SECONDS.length;
    }

    public static int delaySecondsOf(int level) {
        if (level < 1 || level > DELAY_LEVEL_SECONDS.length) {
            return 0;
        }
        return DELAY_LEVEL_SECONDS[level - 1];
    }
}