package com.flashmart.modules.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flashmart.common.context.UserContext;
import com.flashmart.common.exception.BizException;
import com.flashmart.modules.activity.entity.FlashActivity;
import com.flashmart.modules.activity.mapper.FlashActivityMapper;
import com.flashmart.modules.order.entity.SeckillOrder;
import com.flashmart.modules.order.exception.PriceChangedException;
import com.flashmart.modules.order.mapper.SeckillOrderMapper;
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
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单：创建（消费者调用）、查询、支付、超时关单、库存回滚。
 *
 * <p>核心是<b>状态流转的正确性</b>：支付回调与超时关单可能同时发生，
 * 两者都只允许从 PENDING_PAY 出发、且更新时校验 version，
 * 所以最终只有一个能成功 —— 不会出现"已经支付了又被关单把库存退回去"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final SeckillOrderMapper orderMapper;
    private final FlashActivityMapper activityMapper;
    private final ProductMapper productMapper;
    private final SeckillStockService stockService;

    @Value("${flashmart.seckill.order-timeout-seconds:30}")
    private int orderTimeoutSeconds;

    public int timeoutSeconds() {
        return orderTimeoutSeconds;
    }

    /**
     * 创建订单（MQ 消费者调用）。
     *
     * <p>第 0 步是<b>用数据库的当前价格校验一次</b>：抢购时用户看到的价格来自缓存，
     * 而"改活动后删缓存"是有可能失败的（删缓存失败会走补偿，但补偿也可能失败），
     * 那个窗口里就会出现"按旧价成交"。原则是——<b>缓存只负责展示，成交前必须回权威源校验</b>。
     * 校验放在这里而不是抢购接口，是因为消费者本来就要访问数据库，而抢购接口的原则是不碰数据库。
     *
     * @return 创建成功的订单；返回 null 表示这单是重复的，已按幂等处理
     * @throws PriceChangedException 当前价格与用户看到的价格不一致（终态失败，不应重试）
     */
    @Transactional(rollbackFor = Exception.class)
    public SeckillOrder createOrder(String orderNo, Long userId, Long activityId, Long productId, Integer flashPrice) {
        // 0) 用数据库里的当前价格校验：不一致就拒单，避免"展示价 399、成交价 499"这类纠纷
        FlashActivity current = activityMapper.selectById(activityId);
        if (current == null) {
            throw new BizException("活动不存在，本次下单失败");
        }
        if (flashPrice == null || !flashPrice.equals(current.getFlashPrice())) {
            throw new PriceChangedException(flashPrice, current.getFlashPrice());
        }

        // 1) 扣数据库库存：WHERE available_stock > 0 保证数据库这一层也不会扣成负数
        int affected = activityMapper.decreaseStock(activityId);
        if (affected == 0) {
            // 这里只回滚了数据库这一侧；Redis 侧预扣的库存与机会由调用方（消费者）回滚
            throw new BizException("数据库库存已扣不动，本次下单失败");
        }
        try {
            SeckillOrder order = new SeckillOrder();
            order.setOrderNo(orderNo);
            order.setUserId(userId);
            order.setActivityId(activityId);
            order.setProductId(productId);
            order.setFlashPrice(flashPrice);
            order.setStatus(SeckillOrder.STATUS_PENDING_PAY);
            order.setVersion(0);
            order.setCreateTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.insert(order);
            return order;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // /api/seckill 的 unique(user_id, activity_id) 兜底：同一用户重复下单
            // → 把刚扣掉的数据库库存还回去，并让调用方按幂等处理
            activityMapper.increaseStock(activityId);
            log.warn("重复下单已被唯一索引拦下 userId={}, activityId={}", userId, activityId);
            return null;
        }
    }

    public SeckillOrder findByOrderNo(String orderNo) {
        return orderMapper.selectOne(Wrappers.<SeckillOrder>lambdaQuery()
                .eq(SeckillOrder::getOrderNo, orderNo).last("limit 1"));
    }

    /**
     * 订单详情：必须校验归属 —— 只凭订单号就能查别人的订单是典型的越权漏洞。
     * 订单不存在时给出明确的 404 语义，而不是让空对象一路传到序列化阶段报 500。
     */
    public Map<String, Object> detailOfMine(String orderNo) {
        Long userId = requireUserId();
        SeckillOrder order = findByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(404, "订单不存在或还在生成中，请稍后重试");
        }
        if (!userId.equals(order.getUserId())) {
            throw new BizException(403, "无权查看该订单");
        }
        return toVO(order);
    }

    /** 我的订单 */
    public List<Map<String, Object>> myOrders() {
        Long userId = requireUserId();
        return orderMapper.selectList(Wrappers.<SeckillOrder>lambdaQuery()
                        .eq(SeckillOrder::getUserId, userId)
                        .orderByDesc(SeckillOrder::getCreateTime)
                        .last("limit 50"))
                .stream().map(this::toVO).toList();
    }

    /**
     * 支付（模拟支付网关回调）：用乐观锁把 PENDING_PAY → PAID。
     * 返回 0 行说明这一瞬间订单已被"超时关单"或"另一次支付"改过 —— 只有一方能成功。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> pay(String orderNo) {
        Long userId = requireUserId();
        SeckillOrder order = findByOrderNo(orderNo);
        if (order == null) {
            throw new BizException("订单不存在或还在生成中，请稍后重试");
        }
        if (!userId.equals(order.getUserId())) {
            throw new BizException(403, "无权操作该订单");
        }
        // 幂等：已经支付过就直接返回成功，不重复扣款
        if (SeckillOrder.STATUS_PAID.equals(order.getStatus())) {
            return Map.of("orderNo", orderNo, "status", order.getStatus(), "message", "订单已支付，无需重复支付");
        }
        if (!SeckillOrder.STATUS_PENDING_PAY.equals(order.getStatus())) {
            throw new BizException("订单已关闭（" + order.getStatus() + "），无法支付");
        }

        String tradeNo = "PAY" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000);
        int affected = orderMapper.payWithVersion(orderNo, tradeNo, order.getVersion());
        if (affected == 0) {
            // 乐观锁生效：订单状态在本次读取之后被改掉了（多半是被超时关单抢先了）
            throw new BizException("订单状态已变化，请刷新后查看");
        }
        log.info("支付成功 orderNo={}, tradeNo={}", orderNo, tradeNo);
        return Map.of("orderNo", orderNo, "status", SeckillOrder.STATUS_PAID, "message", "支付成功");
    }

    /**
     * 超时关单（延迟消息消费者调用）。
     * 只有从 PENDING_PAY 出发才能关闭；已支付的订单不会被关掉，也不会误回滚库存。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean timeoutOrder(String orderNo) {
        SeckillOrder order = findByOrderNo(orderNo);
        if (order == null) {
            log.warn("超时关单时订单不存在 orderNo={}", orderNo);
            return false;
        }
        int affected = orderMapper.timeoutIfPending(orderNo);
        if (affected == 0) {
            log.info("订单已不是待支付状态，关单跳过 orderNo={}, status={}", orderNo, order.getStatus());
            return false;
        }
        // 关单成功才回滚库存（数据库 + Redis 两边）。
        // 只回滚库存、不回滚"该用户的已购记录"：名额已经用掉，不付款就作废，库存退回给其他人抢。
        activityMapper.increaseStock(order.getActivityId());
        stockService.rollbackStock(order.getActivityId());
        log.info("超时关单并回滚库存（且不退还该用户的抢购机会）orderNo={}, activityId={}", orderNo, order.getActivityId());
        return true;
    }

    /** 商家：某个活动收到的订单（只有活动所属商家能看） */
    public List<Map<String, Object>> ordersOfActivity(Long activityId) {
        Long merchantId = requireUserId();
        FlashActivity activity = activityMapper.selectById(activityId);
        if (activity == null || !merchantId.equals(activity.getMerchantId())) {
            throw new BizException(403, "无权查看该活动的订单");
        }
        return orderMapper.selectList(Wrappers.<SeckillOrder>lambdaQuery()
                        .eq(SeckillOrder::getActivityId, activityId)
                        .orderByDesc(SeckillOrder::getCreateTime))
                .stream().map(this::toVO).toList();
    }

    public Map<String, Object> toVO(SeckillOrder order) {
        FlashActivity activity = activityMapper.selectById(order.getActivityId());
        Product product = order.getProductId() == null ? null : productMapper.selectById(order.getProductId());
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("orderNo", order.getOrderNo());
        vo.put("activityId", order.getActivityId());
        vo.put("activityTitle", activity == null ? "" : activity.getTitle());
        vo.put("productTitle", product == null ? "" : product.getTitle());
        vo.put("coverColor", product == null ? "#4f7cff" : product.getCoverColor());
        vo.put("flashPrice", order.getFlashPrice());
        vo.put("status", order.getStatus());
        vo.put("payTradeNo", order.getPayTradeNo());
        vo.put("createTime", order.getCreateTime() == null ? "" : order.getCreateTime().format(TIME_FORMAT));
        vo.put("payTime", order.getPayTime() == null ? "" : order.getPayTime().format(TIME_FORMAT));
        // 支付截止时间：前端据此显示倒计时
        vo.put("payDeadline", order.getCreateTime() == null ? ""
                : order.getCreateTime().plusSeconds(orderTimeoutSeconds).format(TIME_FORMAT));
        return vo;
    }

    private Long requireUserId() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        return userId;
    }
}
