package com.flashmart.modules.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flashmart.common.cache.RedisCacheClient;
import com.flashmart.common.context.UserContext;
import com.flashmart.common.exception.BizException;
import com.flashmart.modules.activity.entity.FlashActivity;
import com.flashmart.modules.activity.mapper.FlashActivityMapper;
import com.flashmart.modules.cache.service.CacheEvictService;
import com.flashmart.modules.product.entity.Product;
import com.flashmart.modules.product.mapper.ProductMapper;
import com.flashmart.modules.seckill.service.SeckillStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 活动查询与商家侧维护。
 * 读：活动详情走「逻辑过期缓存」，剩余库存在 Redis 里实时扣减，两者职责分开。
 * 写：先更新数据库、再删缓存（Cache Aside），删失败有补偿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final FlashActivityMapper activityMapper;
    private final ProductMapper productMapper;
    private final RedisCacheClient cacheClient;
    private final CacheEvictService cacheEvictService;
    private final SeckillStockService stockService;

    @Value("${flashmart.cache.activity-logic-expire-seconds:60}")
    private long activityLogicExpireSeconds;

    /** 抢购列表：只展示有效活动，按开始时间倒序 */
    public List<Map<String, Object>> listOnline(String keyword) {
        List<FlashActivity> activities = activityMapper.selectList(Wrappers.<FlashActivity>lambdaQuery()
                .eq(FlashActivity::getStatus, FlashActivity.STATUS_ONLINE)
                .like(keyword != null && !keyword.isBlank(), FlashActivity::getTitle, keyword)
                .orderByAsc(FlashActivity::getStartTime)
                .last("limit 30"));
        return activities.stream().map(this::toListItem).toList();
    }

    /** 活动详情：走逻辑过期缓存，缓存未命中或逻辑过期都会自动回源 */
    public Map<String, Object> detail(Long activityId) {
        ActivityCacheVO cached = cacheClient.getWithLogicExpire(
                ActivityCacheKeys.detail(activityId),
                ActivityCacheVO.class,
                activityLogicExpireSeconds,
                () -> loadFromDb(activityId));
        if (cached == null) {
            throw new BizException("活动不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", cached.id());
        result.put("title", cached.title());
        result.put("productId", cached.productId());
        result.put("productTitle", cached.productTitle());
        result.put("description", cached.description());
        result.put("flashPrice", cached.flashPrice());
        result.put("originPrice", cached.originPrice());
        result.put("coverColor", cached.coverColor());
        result.put("totalStock", cached.totalStock());
        result.put("limitPerUser", cached.limitPerUser());
        result.put("startTime", cached.startTime());
        result.put("endTime", cached.endTime());
        result.put("status", cached.status());
        // 剩余库存实时读 Redis（未预热时回退数据库），不放进缓存
        result.put("remainingStock", currentStock(cached.id(), cached.totalStock()));
        result.put("serverTime", LocalDateTime.now().format(TIME_FORMAT));
        return result;
    }

    /** 抢购前校验用的活动信息（直接读缓存对象，避免重复组装 Map） */
    public ActivityCacheVO detailForSeckill(Long activityId) {
        return cacheClient.getWithLogicExpire(
                ActivityCacheKeys.detail(activityId),
                ActivityCacheVO.class,
                activityLogicExpireSeconds,
                () -> loadFromDb(activityId));
    }

    /** 商家：我发布的活动 */
    public List<Map<String, Object>> myActivities() {
        Long merchantId = requireUserId();
        return activityMapper.selectList(Wrappers.<FlashActivity>lambdaQuery()
                        .eq(FlashActivity::getMerchantId, merchantId)
                        .orderByDesc(FlashActivity::getCreateTime))
                .stream().map(this::toListItem).toList();
    }

    /** 商家：创建活动（同时预热 Redis 库存） */
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long productId, String title, Integer flashPrice, Integer totalStock,
                       Integer limitPerUser, LocalDateTime startTime, LocalDateTime endTime) {
        Long merchantId = requireUserId();
        Product product = productMapper.selectById(productId);
        if (product == null || !merchantId.equals(product.getMerchantId())) {
            throw new BizException(403, "只能给自己店铺的商品创建活动");
        }
        if (flashPrice >= product.getOriginPrice()) {
            throw new BizException("抢购价必须低于原价");
        }
        if (endTime.isBefore(startTime)) {
            throw new BizException("结束时间必须晚于开始时间");
        }

        FlashActivity activity = new FlashActivity();
        activity.setProductId(productId);
        activity.setMerchantId(merchantId);
        activity.setTitle(title);
        activity.setFlashPrice(flashPrice);
        activity.setTotalStock(totalStock);
        activity.setAvailableStock(totalStock);
        activity.setLimitPerUser(limitPerUser == null || limitPerUser < 1 ? 1 : limitPerUser);
        activity.setStartTime(startTime);
        activity.setEndTime(endTime);
        activity.setStatus(FlashActivity.STATUS_ONLINE);
        activity.setVersion(0);
        activity.setCreateTime(LocalDateTime.now());
        activity.setUpdateTime(LocalDateTime.now());
        activityMapper.insert(activity);

        prepareStock(activity.getId());
        log.info("商家创建活动活动 id={}, 库存={}", activity.getId(), totalStock);
        return activity.getId();
    }

    /** 商家：修改活动（改库存/时间）→ 先更新数据库，再删缓存 + 重新预热库存 */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long activityId, Integer totalStock, LocalDateTime startTime, LocalDateTime endTime) {
        Long merchantId = requireUserId();
        FlashActivity activity = activityMapper.selectById(activityId);
        if (activity == null || !merchantId.equals(activity.getMerchantId())) {
            throw new BizException(403, "无权修改该活动");
        }
        activity.setTotalStock(totalStock);
        // 已售出数量保持不变：可用库存 = 新总库存 - 已售
        int sold = activity.getTotalStock() - activity.getAvailableStock();
        activity.setAvailableStock(Math.max(0, totalStock - sold));
        activity.setStartTime(startTime);
        activity.setEndTime(endTime);
        activity.setUpdateTime(LocalDateTime.now());
        activityMapper.updateById(activity);

        cacheEvictService.evictWithCompensation(ActivityCacheKeys.detail(activityId));
        // 用 reset 强制覆盖：预热带 10 秒 SETNX 互斥，商家 10 秒内连改两次的话第二次不会生效
        stockService.reset(activityId, activity.getAvailableStock(), activity.getTotalStock());
    }

    /** 把库存写进 Redis（幂等：同一个活动只初始化一次，避免并发覆盖已扣减的库存） */
    public void prepareStock(Long activityId) {
        FlashActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            return;
        }
        stockService.ensurePrepared(activityId,
                () -> activity.getAvailableStock(),
                activity.getTotalStock());
    }

    /** 数据库侧的剩余库存（缓存预热用，避免拿到活动总库存） */
    public int currentDbStock(Long activityId) {
        FlashActivity activity = activityMapper.selectById(activityId);
        return activity == null ? 0 : activity.getAvailableStock();
    }

    /** 实时剩余库存：优先 Redis，未预热时回退数据库 */
    public int currentStock(Long activityId, Integer fallbackStock) {
        return stockService.currentStock(activityId,
                fallbackStock == null ? fallbackStock(activityId) : fallbackStock);
    }

    private int fallbackStock(Long activityId) {
        FlashActivity activity = activityMapper.selectById(activityId);
        return activity == null ? 0 : activity.getAvailableStock();
    }

    private ActivityCacheVO loadFromDb(Long activityId) {
        FlashActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            return null;
        }
        Product product = productMapper.selectById(activity.getProductId());
        return new ActivityCacheVO(
                activity.getId(),
                activity.getProductId(),
                activity.getMerchantId(),
                activity.getTitle(),
                product == null ? "" : product.getTitle(),
                product == null ? "" : product.getDescription(),
                activity.getFlashPrice(),
                product == null ? 0 : product.getOriginPrice(),
                product == null ? "#4f7cff" : product.getCoverColor(),
                activity.getTotalStock(),
                activity.getLimitPerUser(),
                activity.getStartTime().format(TIME_FORMAT),
                activity.getEndTime().format(TIME_FORMAT),
                activity.getStatus());
    }

    private Map<String, Object> toListItem(FlashActivity activity) {
        Product product = productMapper.selectById(activity.getProductId());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", activity.getId());
        item.put("title", activity.getTitle());
        item.put("productTitle", product == null ? "" : product.getTitle());
        item.put("flashPrice", activity.getFlashPrice());
        item.put("originPrice", product == null ? 0 : product.getOriginPrice());
        item.put("coverColor", product == null ? "#4f7cff" : product.getCoverColor());
        item.put("totalStock", activity.getTotalStock());
        item.put("remainingStock", currentStock(activity.getId(), activity.getAvailableStock()));
        item.put("limitPerUser", activity.getLimitPerUser());
        item.put("startTime", activity.getStartTime().format(TIME_FORMAT));
        item.put("endTime", activity.getEndTime().format(TIME_FORMAT));
        item.put("status", activity.getStatus());
        return item;
    }

    private Long requireUserId() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        return userId;
    }
}
