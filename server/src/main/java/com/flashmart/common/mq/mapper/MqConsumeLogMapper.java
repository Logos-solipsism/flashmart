package com.flashmart.common.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashmart.common.mq.entity.MqConsumeLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MqConsumeLogMapper extends BaseMapper<MqConsumeLog> {
}
