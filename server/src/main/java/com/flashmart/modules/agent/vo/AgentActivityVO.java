package com.flashmart.modules.agent.vo;

import com.flashmart.modules.activity.entity.FlashActivity;

/**
 * 给外部 Agent 的活动视图：字段与 {@code GET /api/activity/list} 的列表项保持一致，
 * 只保留契约要求的五项（id/标题/价格/剩余库存/起止时间）+ 拼装展示必需的补充字段。
 *
 * <p>为什么不直接返回 {@link FlashActivity}：实体里有 merchantId、version、deleted、
 * 乐观锁版本等内部字段，一旦序列化出去就成了对外契约的一部分，之后想删都删不掉。
 */
public record AgentActivityVO(
        Long id,
        String title,
        String productTitle,
        Integer flashPrice,
        Integer originPrice,
        Integer totalStock,
        Integer remainingStock,
        String startTime,
        String endTime,
        Integer status) {
}
