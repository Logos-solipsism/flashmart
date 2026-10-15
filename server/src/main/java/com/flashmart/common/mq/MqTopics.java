package com.flashmart.common.mq;

/**
 * Topic 规划。
 * 抢购接口只做"资格校验 + 发消息"，写订单、扣数据库库存、超时关单、缓存补偿全部在消费端完成。
 */
public final class MqTopics {

    /** 抢购下单（削峰：把数据库写入从抢购接口里挪走） */
    public static final String ORDER_CREATE = "topic_order_create";

    /** 订单超时关单（延迟消息，到点由消费者关单并回滚库存） */
    public static final String ORDER_TIMEOUT = "topic_order_timeout";

    /** 缓存删除补偿（删缓存失败时重试，另有延迟双删用同一条链路） */
    public static final String CACHE_EVICT = "topic_cache_evict";

    public static final String CG_ORDER_CREATE = "cg_order_create";
    public static final String CG_ORDER_TIMEOUT = "cg_order_timeout";
    public static final String CG_CACHE_EVICT = "cg_cache_evict";

    private MqTopics() {
    }
}
