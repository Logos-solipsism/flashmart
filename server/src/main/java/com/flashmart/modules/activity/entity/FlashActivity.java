package com.flashmart.modules.activity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 限时抢购活动。 */
@Data
@TableName("flash_activity")
public class FlashActivity {

    public static final int STATUS_ONLINE = 1;
    public static final int STATUS_OFFLINE = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private Long merchantId;

    private String title;

    /** 抢购价（分） */
    private Integer flashPrice;

    private Integer totalStock;

    /** 数据库侧剩余库存：Redis 扣减成功后由消费者异步回写 */
    private Integer availableStock;

    /** 每人限购件数，默认 1（一人一单） */
    private Integer limitPerUser;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer status;

    /** 乐观锁版本号（商家改活动时防止并发覆盖） */
    private Integer version;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 活动是否处于可抢购的时间窗口内 */
    public boolean inProgress(LocalDateTime now) {
        return status != null && status == STATUS_ONLINE
                && now.isAfter(startTime) && now.isBefore(endTime);
    }
}
