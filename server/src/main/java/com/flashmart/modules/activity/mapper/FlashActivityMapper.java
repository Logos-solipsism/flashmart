package com.flashmart.modules.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashmart.modules.activity.entity.FlashActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FlashActivityMapper extends BaseMapper<FlashActivity> {

    /**
     * 扣减数据库侧库存。
     * WHERE 里带 available_stock > 0：即使 Redis 判断失效，数据库这一层也不会扣成负数。
     */
    @Update("UPDATE flash_activity SET available_stock = available_stock - 1, update_time = NOW() "
            + "WHERE id = #{id} AND available_stock > 0")
    int decreaseStock(@Param("id") Long id);

    /** 回滚库存（超时关单 / 下单失败补偿），带上限防止回滚超过总库存 */
    @Update("UPDATE flash_activity SET available_stock = available_stock + 1, update_time = NOW() "
            + "WHERE id = #{id} AND available_stock < total_stock")
    int increaseStock(@Param("id") Long id);
}
