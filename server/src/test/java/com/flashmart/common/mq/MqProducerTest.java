package com.flashmart.common.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 延迟档位换算：RocketMQ 只支持固定档位，业务给的是秒数，必须换算成"不小于它的最小档位"。
 * 换算错会导致"超时关单提前执行"或"迟到很久"，所以单独测。
 */
class MqProducerTest {

    @Test
    @DisplayName("1 秒取第 1 档，30 秒取第 4 档")
    void nearestLevel() {
        assertEquals(1, MqProducer.nearestDelayLevel(1));
        assertEquals(2, MqProducer.nearestDelayLevel(5));
        assertEquals(3, MqProducer.nearestDelayLevel(10));
        assertEquals(4, MqProducer.nearestDelayLevel(30));
    }

    @Test
    @DisplayName("不在档位上的秒数取「不小于它的最小档位」")
    void roundUp() {
        // 3 秒没有对应档位 → 取 5 秒那一档（宁可晚一点，也不能提前关单）
        assertEquals(2, MqProducer.nearestDelayLevel(3));
        assertTrue(MqProducer.delaySecondsOf(MqProducer.nearestDelayLevel(3)) >= 3);
    }

    @Test
    @DisplayName("超过最大档位时取最后一档")
    void beyondMax() {
        int level = MqProducer.nearestDelayLevel(99999);
        assertEquals(18, level);
        assertEquals(7200, MqProducer.delaySecondsOf(level));
    }
}
