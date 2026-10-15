package com.flashmart.modules.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashmart.modules.order.entity.SeckillOrder;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /**
     * 支付回调：用乐观锁把 PENDING_PAY 改成 PAID。
     * WHERE 里同时校验 status 与 version，返回受影响行数；
     * 返回 0 说明这一瞬间订单已经被"超时关单"或"另一次支付回调"改过了 —— 只有一方能成功。
     */
    @Update("UPDATE seckill_order SET status = 'PAID', pay_trade_no = #{tradeNo}, pay_time = NOW(), "
            + "version = version + 1, update_time = NOW() "
            + "WHERE order_no = #{orderNo} AND status = 'PENDING_PAY' AND version = #{version}")
    int payWithVersion(@Param("orderNo") String orderNo,
                       @Param("tradeNo") String tradeNo,
                       @Param("version") Integer version);

    /**
     * 物理删除某个活动的订单（压测重置用）。
     * 不能用 MyBatis-Plus 的 delete：它是逻辑删除，被标记 deleted=1 的行仍然占着
     * unique(user_id, activity_id)，会让同一用户之后再也下不了单。
     */
    @Delete("DELETE FROM seckill_order WHERE activity_id = #{activityId}")
    int deleteByActivityPhysically(@Param("activityId") Long activityId);

    /** 超时关单：同样只允许从 PENDING_PAY 出发（已支付的订单不会被关掉） */
    @Update("UPDATE seckill_order SET status = 'TIMEOUT', cancel_time = NOW(), cancel_reason = 'TIMEOUT', "
            + "version = version + 1, update_time = NOW() "
            + "WHERE order_no = #{orderNo} AND status = 'PENDING_PAY'")
    int timeoutIfPending(@Param("orderNo") String orderNo);
}
