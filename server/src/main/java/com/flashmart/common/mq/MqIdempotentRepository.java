package com.flashmart.common.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flashmart.common.mq.entity.MqConsumeLog;
import com.flashmart.common.mq.mapper.MqConsumeLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 消费幂等：靠 mq_consume_log 的 msg_id 唯一索引。
 * 插入成功 = 第一次消费；插入冲突 = 重复消息，直接丢弃。
 * 状态区分 CONSUMING / SUCCESS / FAILED，方便死信看板看到"卡在处理中"的消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqIdempotentRepository {

    private final MqConsumeLogMapper mapper;

    /** @return true = 首次消费，可以继续处理；false = 重复消息，应直接丢弃 */
    public boolean tryAcquire(String msgId, String topic, String bizKey) {
        MqConsumeLog logRow = new MqConsumeLog();
        logRow.setMsgId(msgId);
        logRow.setTopic(topic);
        logRow.setBizKey(bizKey == null ? "" : bizKey);
        logRow.setStatus(MqConsumeLog.STATUS_CONSUMING);
        logRow.setRetryCount(0);
        logRow.setErrorMsg("");
        logRow.setCreateTime(LocalDateTime.now());
        logRow.setUpdateTime(LocalDateTime.now());
        try {
            mapper.insert(logRow);
            return true;
        } catch (DuplicateKeyException e) {
            MqConsumeLog exists = mapper.selectOne(Wrappers.<MqConsumeLog>lambdaQuery()
                    .eq(MqConsumeLog::getMsgId, msgId).last("limit 1"));
            // 之前处理失败过的消息允许重试，成功过的不再重复处理
            if (exists != null && MqConsumeLog.STATUS_FAILED.equals(exists.getStatus())) {
                exists.setStatus(MqConsumeLog.STATUS_CONSUMING);
                exists.setRetryCount(exists.getRetryCount() == null ? 1 : exists.getRetryCount() + 1);
                exists.setUpdateTime(LocalDateTime.now());
                mapper.updateById(exists);
                return true;
            }
            return false;
        }
    }

    public void markSuccess(String msgId) {
        updateStatus(msgId, MqConsumeLog.STATUS_SUCCESS, "");
    }

    public void markFailed(String msgId, String errorMsg) {
        String msg = errorMsg == null ? "" : errorMsg.substring(0, Math.min(400, errorMsg.length()));
        updateStatus(msgId, MqConsumeLog.STATUS_FAILED, msg);
    }

    private void updateStatus(String msgId, String status, String errorMsg) {
        MqConsumeLog update = new MqConsumeLog();
        update.setStatus(status);
        update.setErrorMsg(errorMsg);
        update.setUpdateTime(LocalDateTime.now());
        mapper.update(update, Wrappers.<MqConsumeLog>lambdaUpdate().eq(MqConsumeLog::getMsgId, msgId));
    }
}
