package com.flashmart.modules.seckill.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/** 订单号：时间戳 + 用户尾号 + 随机数，足够避免重复（数据库还有唯一索引兜底）。 */
public final class OrderNoGenerator {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private OrderNoGenerator() {
    }

    public static String next(Long userId) {
        return "FM" + LocalDateTime.now().format(FORMAT)
                + String.format("%03d", userId % 1000)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
