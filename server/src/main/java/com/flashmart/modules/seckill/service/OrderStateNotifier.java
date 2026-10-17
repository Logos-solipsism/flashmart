package com.flashmart.modules.seckill.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 抢购结果的"过程状态"留痕。
 *
 * <p>为什么需要它：抢购接口是异步的——Redis 扣减成功就返回了 orderNo，
 * 订单由消费者稍后落库。如果消费者最终没把订单写进去（数据库库存扣不动、
 * 或者撞了唯一索引），前端拿 orderNo 轮询只会一直拿到"排队中"，
 * <b>用户既不知道自己失败了，也不知道名额已经被退回、可以再抢</b>。
 *
 * <p>所以这里在 Redis 留两个短生命周期的标记：
 * <ul>
 *   <li><code>seckill:fail:{orderNo}</code>：这一单确定失败了（名额与库存已回滚），带原因；</li>
 *   <li><code>seckill:retry:{orderNo}</code>：这一次处理失败、但 MQ 还会重试，属于中间态。</li>
 * </ul>
 * 订单一旦落库成功就把标记删掉——因为"订单存在"才是唯一可信的结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStateNotifier {

    /** 失败标记保留时间：足够用户在前端看到，又不至于长期占内存 */
    private static final Duration FAIL_TTL = Duration.ofMinutes(10);
    /** 重试中标记保留时间：比一次阶梯重试的间隔长一点即可 */
    private static final Duration RETRY_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redis;

    public void markFailed(String orderNo, String reason) {
        redis.opsForValue().set(failKey(orderNo), reason, FAIL_TTL);
        redis.delete(retryKey(orderNo));
        log.warn("抢购结果标记为失败 orderNo={}, 原因={}", orderNo, reason);
    }

    public void markRetrying(String orderNo, String reason) {
        redis.opsForValue().set(retryKey(orderNo), reason, RETRY_TTL);
    }

    /** 订单落库成功后清掉所有过程标记 */
    public void markSucceeded(String orderNo) {
        redis.delete(failKey(orderNo));
        redis.delete(retryKey(orderNo));
    }

    public String failedReason(String orderNo) {
        return redis.opsForValue().get(failKey(orderNo));
    }

    public String retryingReason(String orderNo) {
        return redis.opsForValue().get(retryKey(orderNo));
    }

    private String failKey(String orderNo) {
        return "seckill:fail:" + orderNo;
    }

    private String retryKey(String orderNo) {
        return "seckill:retry:" + orderNo;
    }
}
