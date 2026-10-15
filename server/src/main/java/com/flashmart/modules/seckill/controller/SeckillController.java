package com.flashmart.modules.seckill.controller;

import com.flashmart.common.limiter.SlidingWindowLimit;
import com.flashmart.common.result.R;
import com.flashmart.modules.seckill.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "抢购")
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;

    /**
     * 抢购接口：按用户维度 1 秒最多 5 次。
     * 之所以要限流：抢购接口是最容易被脚本刷的入口，刷进来一次就消耗一份库存。
     */
    @Operation(summary = "抢购（1 秒内每人最多 5 次）")
    @SlidingWindowLimit(key = "seckill", windowSeconds = 1, maxRequests = 5,
            dimension = SlidingWindowLimit.Dimension.USER, message = "手速太快啦，歇一秒再抢")
    @PostMapping("/{activityId}")
    public R<Map<String, Object>> seckill(@PathVariable Long activityId) {
        return R.ok(seckillService.seckill(activityId));
    }

    @Operation(summary = "查询抢购结果（前端轮询：QUEUING / PENDING_PAY / PAID / TIMEOUT）")
    @GetMapping("/result/{orderNo}")
    public R<Map<String, Object>> result(@PathVariable String orderNo) {
        return R.ok(seckillService.result(orderNo));
    }
}
