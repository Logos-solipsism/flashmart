package com.flashmart.modules.agent.controller;

import com.flashmart.common.result.R;
import com.flashmart.modules.agent.service.AgentQueryService;
import com.flashmart.modules.agent.vo.AgentActivityVO;
import com.flashmart.modules.agent.vo.AgentOrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 外部 Agent 服务的只读接口。
 *
 * <p>三条约定，改这里之前先读一遍：
 * <ol>
 *   <li><b>全部 GET</b>：没有 POST/PUT/DELETE，外部服务无法通过这些接口改变任何状态；</li>
 *   <li><b>全部只读</b>：只查不改，尤其订单 —— 状态流转只走支付回调与超时关单两条既有链路；</li>
 *   <li><b>统一走既有 R</b>：响应形状与项目其它接口一致，调用方不用为这组接口写第二套解析。</li>
 * </ol>
 *
 * <p>鉴权不在这里做，由 {@code AgentAuthInterceptor} 统一拦在入口（未配置令牌时一律 401）。
 */
@Tag(name = "Agent 只读接口（外部服务）")
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentQueryService agentQueryService;

    @Operation(summary = "健康检查", description = "连通性与令牌校验探针；能返回就说明令牌是对的")
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        return R.ok(Map.of("ok", true));
    }

    @Operation(summary = "活动列表（只读）", description = "复用买家侧活动列表查询，返回 id/标题/价格/剩余库存/起止时间")
    @GetMapping("/activities")
    public R<List<AgentActivityVO>> activities(@RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "20") int limit) {
        return R.ok(agentQueryService.activities(keyword, limit));
    }

    @Operation(summary = "订单查询（只读）", description = "按 userId 或 orderNo 查，绝不修改订单状态")
    @GetMapping("/orders")
    public R<List<AgentOrderVO>> orders(@RequestParam(required = false) Long userId,
                                        @RequestParam(required = false) String orderNo) {
        return R.ok(agentQueryService.orders(userId, orderNo));
    }
}
