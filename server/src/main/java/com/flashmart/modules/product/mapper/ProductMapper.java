package com.flashmart.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashmart.modules.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
