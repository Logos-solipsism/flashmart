package com.flashmart.modules.order.controller;

import com.flashmart.common.limiter.SlidingWindowLimit;
import com.flashmart.common.result.R;
import com.flashmart.modules.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "订单")
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "我的订单")
    @GetMapping("/mine")
    public R<List<Map<String, Object>>> mine() {
        return R.ok(orderService.myOrders());
    }

    @Operation(summary = "订单详情（只能看自己的订单）")
    @GetMapping("/{orderNo}")
    public R<Map<String, Object>> detail(@PathVariable String orderNo) {
        return R.ok(orderService.detailOfMine(orderNo));
    }

    /** 支付接口按用户维度限流：防止脚本反复调用支付 */
    @Operation(summary = "支付（模拟支付网关回调）")
    @SlidingWindowLimit(key = "order_pay", windowSeconds = 1, maxRequests = 3,
            dimension = SlidingWindowLimit.Dimension.USER, message = "支付操作过于频繁，请稍后再试")
    @PostMapping("/{orderNo}/pay")
    public R<Map<String, Object>> pay(@PathVariable String orderNo) {
        return R.ok(orderService.pay(orderNo));
    }
}
