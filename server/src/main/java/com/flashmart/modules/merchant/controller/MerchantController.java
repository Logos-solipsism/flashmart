package com.flashmart.modules.merchant.controller;

import com.flashmart.common.context.UserContext;
import com.flashmart.common.exception.BizException;
import com.flashmart.common.result.R;
import com.flashmart.modules.activity.service.ActivityService;
import com.flashmart.modules.auth.entity.SysUser;
import com.flashmart.modules.order.service.OrderService;
import com.flashmart.modules.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Tag(name = "商家后台")
@RestController
@RequestMapping("/api/merchant")
@RequiredArgsConstructor
public class MerchantController {

    private final ProductService productService;
    private final ActivityService activityService;
    private final OrderService orderService;

    @Operation(summary = "创建商品")
    @PostMapping("/product")
    public R<Long> createProduct(@RequestBody @Validated ProductRequest request) {
        requireMerchant();
        return R.ok(productService.create(request.title(), request.description(),
                request.originPrice(), request.coverColor()));
    }

    /** 商家接口的角色校验：前端把菜单藏起来只是体验，接口这一层必须自己挡 */
    private void requireMerchant() {
        if (!SysUser.ROLE_MERCHANT.equals(UserContext.role())) {
            throw new BizException(403, "只有商家账号可以执行该操作");
        }
    }

    @Operation(summary = "我店铺的商品")
    @GetMapping("/product/mine")
    public R<List<Map<String, Object>>> myProducts() {
        requireMerchant();
        return R.ok(productService.mine());
    }

    @Operation(summary = "创建抢购活动（会同步预热 Redis 库存）")
    @PostMapping("/activity")
    public R<Long> createActivity(@RequestBody @Validated ActivityRequest request) {
        requireMerchant();
        return R.ok(activityService.create(request.productId(), request.title(), request.flashPrice(),
                request.totalStock(), request.limitPerUser(), request.startTime(), request.endTime()));
    }

    @Operation(summary = "修改活动（改库存/时间：先更新数据库，再删缓存并重新预热）")
    @PutMapping("/activity/{id}")
    public R<Void> updateActivity(@PathVariable Long id, @RequestBody @Validated ActivityUpdateRequest request) {
        requireMerchant();
        activityService.update(id, request.totalStock(), request.startTime(), request.endTime());
        return R.ok();
    }

    @Operation(summary = "我发布的活动")
    @GetMapping("/activity/mine")
    public R<List<Map<String, Object>>> myActivities() {
        requireMerchant();
        return R.ok(activityService.myActivities());
    }

    @Operation(summary = "某个活动收到的订单")
    @GetMapping("/activity/{id}/orders")
    public R<List<Map<String, Object>>> activityOrders(@PathVariable Long id) {
        requireMerchant();
        return R.ok(orderService.ordersOfActivity(id));
    }

    public record ProductRequest(@NotBlank String title, String description,
                                 @NotNull Integer originPrice, String coverColor) {
    }

    public record ActivityRequest(@NotNull Long productId, @NotBlank String title,
                                  @NotNull Integer flashPrice, @NotNull Integer totalStock,
                                  Integer limitPerUser,
                                  @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
                                  @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
    }

    public record ActivityUpdateRequest(@NotNull Integer totalStock,
                                        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
                                        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
    }
}
