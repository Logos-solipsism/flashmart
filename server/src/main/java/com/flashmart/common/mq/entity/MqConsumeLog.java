package com.flashmart.common.mq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 消费幂等表：msg_id 唯一索引，插入成功代表首次消费。 */
@Data
@TableName("mq_consume_log")
public class MqConsumeLog {

    public static final String STATUS_CONSUMING = "CONSUMING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String msgId;

    private String topic;

    private String bizKey;

    private String status;

    private Integer retryCount;

    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
