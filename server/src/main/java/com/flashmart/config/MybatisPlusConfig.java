package com.flashmart.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>加上乐观锁插件后，实体上标了 @Version 的字段才会在 updateById 时自动带上版本条件
 * （UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?），
 * 并发更新时只有一个能成功，另一个返回 0 行。
 *
 * <p>注意：订单表的支付/关单是<b>手写 SQL 显式校验 status 与 version</b> 的，
 * 那种写法比插件更直观（能一眼看出"只允许从 PENDING_PAY 出发"），两种方式在这个项目里并存。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
