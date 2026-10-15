package com.flashmart.modules.seckill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 订单号生成：格式、长度、并发下的唯一性（数据库还有唯一索引兜底，但生成层不能轻易撞）。 */
class OrderNoGeneratorTest {

    @Test
    @DisplayName("订单号以 FM 开头，长度固定")
    void format() {
        String orderNo = OrderNoGenerator.next(9003L);
        assertTrue(orderNo.startsWith("FM"), "订单号应以 FM 开头: " + orderNo);
        assertEquals(2 + 14 + 3 + 6, orderNo.length(), "订单号长度应为 25: " + orderNo);
    }

    @Test
    @DisplayName("批量生成不重复")
    void unique() {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < 20000; i++) {
            set.add(OrderNoGenerator.next(9003L));
        }
        assertEquals(20000, set.size(), "两万个订单号不应出现重复");
    }
}
