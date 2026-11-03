package com.flashmart.modules.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flashmart.common.exception.BizException;
import com.flashmart.modules.activity.entity.FlashActivity;
import com.flashmart.modules.activity.mapper.FlashActivityMapper;
import com.flashmart.modules.activity.service.ActivityService;
import com.flashmart.modules.agent.vo.AgentActivityVO;
import com.flashmart.modules.agent.vo.AgentOrderVO;
import com.flashmart.modules.order.entity.SeckillOrder;
import com.flashmart.modules.order.mapper.SeckillOrderMapper;
import com.flashmart.modules.order.service.OrderService;
import com.flashmart.modules.product.entity.Product;
import com.flashmart.modules.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 外部 Agent 服务的只读查询。
 *
 * <p>全部方法只读：不写库、不改订单状态、不碰 Redis 的库存键。
 * 「只读」在这里是结构性的而不是约定性的——这个类没有任何写操作的依赖入口
 * （不需要 CacheEvictService、不需要 SeckillStockService），想写也没有通道。
 *
 * <p>活动列表复用 {@link ActivityService#listOnline}，与买家侧看到的是同一份数据；
 * 剩余库存在那里已经按「Redis 实时值优先、未预热回退数据库」取过。
 * 订单则直接读 Mapper 组装 VO：{@link OrderService} 的公开查询方法要么依赖
 * UserContext（Agent 没有登录用户），要么会按操作者身份做归属校验，都不适合直接复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentQueryService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** 默认与上限：给外部服务留个口子，但不允许它把整张表拉走 */
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ActivityService activityService;
    private final FlashActivityMapper activityMapper;
    private final SeckillOrderMapper orderMapper;
    private final ProductMapper productMapper;

    /** 活动列表（只读）：契约只要求五项，其余保持与买家侧列表一致 */
    public List<AgentActivityVO> activities(String keyword, int limit) {
        return activityService.listOnline(keyword).stream()
                .limit(normalizeLimit(limit))
                .map(item -> new AgentActivityVO(
                        asLong(item.get("id")),
                        asString(item.get("title")),
                        asString(item.get("productTitle")),
                        asInteger(item.get("flashPrice")),
                        asInteger(item.get("originPrice")),
                        asInteger(item.get("totalStock")),
                        asInteger(item.get("remainingStock")),
                        asString(item.get("startTime")),
                        asString(item.get("endTime")),
                        asInteger(item.get("status"))))
                .toList();
    }

    /**
     * 订单查询（只读）：userId 或 orderNo 至少给一个。
     * 两个条件都给时取交集，而不是二选一 —— 「传了却没生效」的过滤条件比报错更难排查。
     */
    public List<AgentOrderVO> orders(Long userId, String orderNo) {
        boolean hasOrderNo = orderNo != null && !orderNo.isBlank();
        if (userId == null && !hasOrderNo) {
            throw new BizException("userId 与 orderNo 至少提供一个");
        }
        List<SeckillOrder> rows = orderMapper.selectList(Wrappers.<SeckillOrder>lambdaQuery()
                .eq(userId != null, SeckillOrder::getUserId, userId)
                .eq(hasOrderNo, SeckillOrder::getOrderNo, hasOrderNo ? orderNo.trim() : null)
                .orderByDesc(SeckillOrder::getCreateTime)
                .last("limit " + MAX_LIMIT));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        // 关联数据按 id 去重后批量取，避免每行都回查一次活动与商品
        List<Long> activityIds = rows.stream().map(SeckillOrder::getActivityId).filter(Objects::nonNull).distinct().toList();
        List<Long> productIds = rows.stream().map(SeckillOrder::getProductId).filter(Objects::nonNull).distinct().toList();
        List<FlashActivity> activities = activityIds.isEmpty() ? List.of() : activityMapper.selectBatchIds(activityIds);
        List<Product> products = productIds.isEmpty() ? List.of() : productMapper.selectBatchIds(productIds);

        return rows.stream().map(order -> new AgentOrderVO(
                        order.getOrderNo(),
                        order.getUserId(),
                        order.getActivityId(),
                        activities.stream().filter(a -> Objects.equals(a.getId(), order.getActivityId()))
                                .map(FlashActivity::getTitle).findFirst().orElse(""),
                        products.stream().filter(p -> Objects.equals(p.getId(), order.getProductId()))
                                .map(Product::getTitle).findFirst().orElse(""),
                        order.getFlashPrice(),
                        order.getStatus(),
                        order.getPayTradeNo(),
                        order.getCreateTime() == null ? "" : order.getCreateTime().format(TIME_FORMAT),
                        order.getPayTime() == null ? "" : order.getPayTime().format(TIME_FORMAT)))
                .toList();
    }

    /** limit 归一化：不传（或非正数）用默认值，超上限按上限算 —— 绝不能悄悄变成「不限」 */
    static int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
