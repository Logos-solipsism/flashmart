package com.flashmart.modules.order.exception;

/**
 * 下单时发现价格与用户看到的不一致（商家改价了）。
 *
 * <p>为什么单独一个异常：这是一种<b>终态的业务失败</b>——
 * 重试一百次价格也不会变回来，所以消费者要按"确定失败"处理（回滚名额 + 明确提示用户刷新重抢），
 * 而不是交给 MQ 一直重试。
 */
public class PriceChangedException extends RuntimeException {

    private final Integer seenPrice;
    private final Integer currentPrice;

    public PriceChangedException(Integer seenPrice, Integer currentPrice) {
        super("价格已变化：下单时看到 " + seenPrice + " 分，当前是 " + currentPrice + " 分");
        this.seenPrice = seenPrice;
        this.currentPrice = currentPrice;
    }

    public Integer getSeenPrice() {
        return seenPrice;
    }

    public Integer getCurrentPrice() {
        return currentPrice;
    }
}
