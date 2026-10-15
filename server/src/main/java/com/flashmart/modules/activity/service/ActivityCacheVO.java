package com.flashmart.modules.activity.service;

/**
 * 活动详情缓存对象。
 * 时间统一用 ISO 字符串，避免缓存反序列化时依赖 LocalDateTime 的序列化配置。
 * 注意：这里<b>不缓存剩余库存</b>——库存是秒级变化的字段，缓存它必然出现"页面显示有货但抢不到"，
 * 所以库存单独实时读 Redis。
 */
public record ActivityCacheVO(
        Long id,
        Long productId,
        Long merchantId,
        String title,
        String productTitle,
        String description,
        Integer flashPrice,
        Integer originPrice,
        String coverColor,
        Integer totalStock,
        Integer limitPerUser,
        String startTime,
        String endTime,
        Integer status) {
}
