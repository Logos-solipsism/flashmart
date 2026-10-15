package com.flashmart.modules.activity.service;

/** 活动相关的 Redis key 集中在这里，避免各处硬编码字符串。 */
public final class ActivityCacheKeys {

    private ActivityCacheKeys() {
    }

    /** 活动详情缓存（逻辑过期） */
    public static String detail(Long activityId) {
        return "activity:detail:" + activityId;
    }

    /** 抢购库存（扣减的真身） */
    public static String stock(Long activityId) {
        return "seckill:stock:" + activityId;
    }

    /** 已购记录：Hash，userId -> 已购数量（一人一单就靠它） */
    public static String bought(Long activityId) {
        return "seckill:bought:" + activityId;
    }

    /** 库存预热互斥 key */
    public static String stockInit(Long activityId) {
        return "seckill:stock:init:" + activityId;
    }
}
