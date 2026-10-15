package com.flashmart.modules.product.controller;

import com.flashmart.common.result.R;
import com.flashmart.modules.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "商品（买家侧）")
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "在售商品列表")
    @GetMapping("/list")
    public R<List<Map<String, Object>>> list() {
        return R.ok(productService.listOnline());
    }
}
