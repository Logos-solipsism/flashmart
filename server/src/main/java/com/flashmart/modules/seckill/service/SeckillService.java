package com.flashmart.modules.seckill.service;

import com.flashmart.common.context.UserContext;
import com.flashmart.common.exception.BizException;
import com.flashmart.common.mq.MqEvents;
import com.flashmart.common.mq.MqProducer;
import com.flashmart.common.mq.MqTopics;
import com.flashmart.modules.activity.entity.FlashActivity;
import com.flashmart.modules.activity.service.ActivityCacheVO;
import com.flashmart.modules.activity.service.ActivityService;
import com.flashmart.modules.order.entity.SeckillOrder;
import com.flashmart.modules.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 抢购主链路。整条链路只做四件事，绝不碰数据库写：
 * ① 校验活动（走缓存）；② Redis + Lua 原子扣库存与限购校验；
 * ③ 生成订单号；④ 投递 MQ 后立刻返回。
 *
 * <p>写订单、扣数据库库存、发延迟关单消息全部由消费者完成 —— 这就是"流量削峰"：
 * 数据库只面对 MQ 的消费速度，而不是用户点击的瞬时并发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private final ActivityService activityService;
    private final SeckillStockService stockService;
    private final OrderService orderService;
    private final MqProducer mqProducer;

    public Map<String, Object> seckill(Long activityId) {
        Long userId = requireUserId();

        // ① 活动校验：详情来自逻辑过期缓存，活动不存在时缓存里是空值标记，这里直接判定
        ActivityCacheVO activity = activityService.detailForSeckill(activityId);
        if (activity == null) {
            throw new BizException("活动不存在");
        }
        if (activity.status() == null || activity.status() != FlashActivity.STATUS_ONLINE) {
            throw new BizException("活动已下线");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(LocalDateTime.parse(activity.startTime()))) {
            throw new BizException("活动还没开始，倒计时结束后再来抢");
        }
        if (now.isAfter(LocalDateTime.parse(activity.endTime()))) {
            throw new BizException("活动已结束");
        }

        // ② 库存预热（懒加载，幂等）+ Lua 原子扣减
        stockService.prepare(activityId, activity.totalStock());
        long result = stockService.trySeckill(activityId, userId, activity.limitPerUser());
        if (result == SeckillStockService.OVER_LIMIT) {
            throw new BizException("每人限购 " + activity.limitPerUser() + " 件，你已经抢到啦");
        }
        if (result == SeckillStockService.SOLD_OUT) {
            throw new BizException("手慢了，已被抢光");
        }
        if (result == SeckillStockService.NOT_PREPARED) {
            throw new BizException("活动库存尚未就绪，请稍后重试");
        }

        // ③ 生成订单号 ④ 投递 MQ 后立刻返回（前端拿 orderNo 轮询结果）
        String orderNo = OrderNoGenerator.next(userId);
        mqProducer.sendAsync(MqTopics.ORDER_CREATE, "order:" + orderNo,
                new MqEvents.OrderCreateEvent(orderNo, userId, activityId, activity.productId(), activity.flashPrice()));
        log.info("抢购成功进入队列 userId={}, activityId={}, orderNo={}, 剩余库存={}", userId, activityId, orderNo, result);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderNo", orderNo);
        response.put("state", "QUEUING");
        response.put("message", "抢到了！正在为你生成订单");
        return response;
    }

    /** 轮询抢购结果：订单还没落库时返回 QUEUING，前端据此显示"排队中" */
    public Map<String, Object> result(String orderNo) {
        Long userId = requireUserId();
        SeckillOrder order = orderService.findByOrderNo(orderNo);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderNo", orderNo);
        if (order == null) {
            response.put("state", "QUEUING");
            return response;
        }
        if (!userId.equals(order.getUserId())) {
            throw new BizException(403, "无权查看该订单");
        }
        response.put("state", order.getStatus());
        response.put("order", orderService.toVO(order));
        return response;
    }

    private Long requireUserId() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        return userId;
    }
}
