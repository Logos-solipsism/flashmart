package com.flashmart.modules.agent.vo;

/**
 * 给外部 Agent 的订单视图（只读快照）。
 *
 * <p>刻意<b>不</b>包含 version / cancelReason 这类内部字段，也不提供任何写操作方法 ——
 * 这组接口的原则是「只能看，不能动」：订单的状态流转只发生在支付回调与超时关单两条既有链路上。
 */
public record AgentOrderVO(
        String orderNo,
        Long userId,
        Long activityId,
        String activityTitle,
        String productTitle,
        Integer flashPrice,
        String status,
        String payTradeNo,
        String createTime,
        String payTime) {
}
