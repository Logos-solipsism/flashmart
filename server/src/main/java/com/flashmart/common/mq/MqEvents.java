package com.flashmart.common.mq;

/**
 * 消息体定义。
 * 只带业务主键（orderNo / activityId）和必要字段，消费端自己回查数据库，
 * 这样消息格式不会随业务字段变化而频繁升级。
 */
public final class MqEvents {

    private MqEvents() {
    }

    /** 抢购下单事件 */
    public record OrderCreateEvent(String orderNo, Long userId, Long activityId, Long productId, Integer flashPrice) {
    }

    /** 订单超时关单事件（延迟消息） */
    public record OrderTimeoutEvent(String orderNo) {
    }

    /**
     * 缓存删除事件。
     *
     * @param cacheKey 需要删除的缓存 key
     * @param reason   触发原因（UPDATE / DOUBLE_DELETE）
     */
    public record CacheEvictEvent(String cacheKey, String reason) {
    }
}
