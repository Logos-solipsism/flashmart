package com.flashmart.modules.activity.controller;

import com.flashmart.common.result.R;
import com.flashmart.modules.activity.service.ActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "抢购活动（买家侧）")
@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    @Operation(summary = "抢购活动列表")
    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword) {
        return R.ok(activityService.listOnline(keyword));
    }

    @Operation(summary = "活动详情（逻辑过期缓存 + 剩余库存实时读 Redis）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(activityService.detail(id));
    }
}
