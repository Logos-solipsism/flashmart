package com.flashmart.modules.bench;

import com.flashmart.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 压测专用接口（bench profile 才注册，正常启动不会暴露）。 */
@Tag(name = "压测（bench profile）")
@RestController
@RequestMapping("/api/bench")
@Profile("bench")
@RequiredArgsConstructor
public class BenchController {

    private final BenchService benchService;

    @Operation(summary = "重置活动库存与订单（压测前清场）")
    @PostMapping("/reset/{activityId}")
    public R<Map<String, Object>> reset(@PathVariable Long activityId,
                                        @RequestParam(defaultValue = "100") int stock,
                                        @RequestParam(defaultValue = "true") boolean clearOrders) {
        return R.ok(benchService.reset(activityId, stock, clearOrders));
    }

    @Operation(summary = "异步版抢购（Lua + MQ，合成用户，跳过一人一单）")
    @PostMapping("/seckill-async/{activityId}")
    public R<Map<String, Object>> seckillAsync(@PathVariable Long activityId,
                                               @RequestParam(required = false) Long syntheticUserId) {
        return R.ok(benchService.seckillAsyncSynthetic(activityId, syntheticUserId));
    }

    @Operation(summary = "同步版抢购（对照组：同步扣库存 + 写订单）")
    @PostMapping("/seckill-sync/{activityId}")
    public R<Map<String, Object>> seckillSync(@PathVariable Long activityId,
                                              @RequestParam(required = false) Long syntheticUserId) {
        return R.ok(benchService.seckillSync(activityId, syntheticUserId));
    }

    @Operation(summary = "活动库存与订单统计（验证有没有超卖）")
    @GetMapping("/stats/{activityId}")
    public R<Map<String, Object>> stats(@PathVariable Long activityId) {
        return R.ok(benchService.stats(activityId));
    }

    @Operation(summary = "立即触发超时关单（延迟 1 秒）")
    @PostMapping("/force-timeout/{orderNo}")
    public R<Map<String, Object>> forceTimeout(@PathVariable String orderNo) {
        return R.ok(benchService.forceTimeout(orderNo));
    }

    @Operation(summary = "查询某个 key 的缓存回源次数")
    @GetMapping("/cache-load-count")
    public R<Map<String, Object>> cacheLoadCount(@RequestParam String key) {
        return R.ok(benchService.cacheLoadCount(key));
    }

    @Operation(summary = "重置某个 key 的回源计数")
    @PostMapping("/cache-load-count/reset")
    public R<Map<String, Object>> resetCacheLoadCount(@RequestParam String key) {
        return R.ok(benchService.resetCacheLoadCount(key));
    }

    @Operation(summary = "黑名单取证：Lua 调用次数 / 黑名单拒绝次数 / 某 IP 是否被封")
    @GetMapping("/blacklist-stats")
    public R<Map<String, Object>> blacklistStats(@RequestParam(required = false) String ip) {
        return R.ok(benchService.blacklistStats(ip));
    }

    @Operation(summary = "重置黑名单（给 ip 只清该 IP；不给则清所有 black:* 键并清零计数器）")
    @PostMapping("/blacklist/reset")
    public R<Map<String, Object>> resetBlacklist(@RequestParam(required = false) String ip) {
        return R.ok(benchService.resetBlacklist(ip));
    }
}
