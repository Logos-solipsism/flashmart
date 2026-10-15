package com.flashmart.modules.product.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flashmart.common.context.UserContext;
import com.flashmart.common.exception.BizException;
import com.flashmart.modules.product.entity.Product;
import com.flashmart.modules.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;

    /** 买家侧：在售商品 */
    public List<Map<String, Object>> listOnline() {
        return productMapper.selectList(Wrappers.<Product>lambdaQuery()
                        .eq(Product::getStatus, 1)
                        .orderByDesc(Product::getCreateTime)
                        .last("limit 50"))
                .stream().map(this::toVO).toList();
    }

    /** 商家侧：我店铺的商品 */
    public List<Map<String, Object>> mine() {
        Long merchantId = requireUserId();
        return productMapper.selectList(Wrappers.<Product>lambdaQuery()
                        .eq(Product::getMerchantId, merchantId)
                        .orderByDesc(Product::getCreateTime))
                .stream().map(this::toVO).toList();
    }

    public Long create(String title, String description, Integer originPrice, String coverColor) {
        Long merchantId = requireUserId();
        Product product = new Product();
        product.setMerchantId(merchantId);
        product.setTitle(title);
        product.setDescription(description == null ? "" : description);
        product.setOriginPrice(originPrice);
        product.setCoverColor(coverColor == null || coverColor.isBlank() ? "#4f7cff" : coverColor);
        product.setStatus(1);
        product.setCreateTime(LocalDateTime.now());
        product.setUpdateTime(LocalDateTime.now());
        productMapper.insert(product);
        return product.getId();
    }

    private Map<String, Object> toVO(Product product) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", product.getId());
        vo.put("title", product.getTitle());
        vo.put("description", product.getDescription());
        vo.put("originPrice", product.getOriginPrice());
        vo.put("coverColor", product.getCoverColor());
        vo.put("status", product.getStatus());
        return vo;
    }

    private Long requireUserId() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        return userId;
    }
}
