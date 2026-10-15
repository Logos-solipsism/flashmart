package com.flashmart.modules.seckill.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订单号：FM + 秒级时间戳 + 用户尾号 3 位 + 6 位<b>自增序列</b>。
 *
 * <p>为什么用自增序列而不是随机数：同一个用户在同一秒内可能下多单（压测、脚本重放场景），
 * 随机数在小样本上碰撞概率可以忽略，但订单号上有唯一索引，一旦碰撞就是下单直接失败。
 * 自增序列在单进程内保证不重复，配合时间戳与用户尾号，重复概率可以忽略。
 *
 * <p>边界（如实记录）：多实例部署时各实例的序列是独立的，理论上存在同一秒撞号的可能，
 * 那时应换成全局序列（Redis INCR）或雪花算法；数据库的 order_no 唯一索引是最后一道兜底。
 */
public final class OrderNoGenerator {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int SEQ_BOUND = 1_000_000;

    /** 进程内自增序列，起始值随机，避免多实例同时启动时序列完全同步 */
    private static final AtomicInteger SEQ = new AtomicInteger(ThreadLocalRandom.current().nextInt(SEQ_BOUND));

    private OrderNoGenerator() {
    }

    public static String next(Long userId) {
        int seq = Math.floorMod(SEQ.incrementAndGet(), SEQ_BOUND);
        return "FM" + LocalDateTime.now().format(FORMAT)
                + String.format("%03d", userId % 1000)
                + String.format("%06d", seq);
    }
}
