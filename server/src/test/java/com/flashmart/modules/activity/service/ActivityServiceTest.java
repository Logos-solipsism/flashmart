package com.flashmart.modules.activity.service;

import com.flashmart.common.cache.RedisCacheClient;
import com.flashmart.common.context.UserContext;
import com.flashmart.common.exception.BizException;
import com.flashmart.modules.activity.entity.FlashActivity;
import com.flashmart.modules.activity.mapper.FlashActivityMapper;
import com.flashmart.modules.cache.service.CacheEvictService;
import com.flashmart.modules.product.mapper.ProductMapper;
import com.flashmart.modules.seckill.service.SeckillStockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家改活动库存：已售是既成事实，不能被抹掉。
 *
 * <p>这条测试是踩过坑之后补的——最初我把"先覆盖总库存、再算已售"的顺序写反了，
 * 结果可用库存被算成原值不变，等于把少掉的部分当成没卖出去，会超卖。
 */
class ActivityServiceTest {

    private final FlashActivityMapper activityMapper = mock(FlashActivityMapper.class);
    private final CacheEvictService cacheEvictService = mock(CacheEvictService.class);
    private final SeckillStockService stockService = mock(SeckillStockService.class);

    private ActivityService service() {
        return new ActivityService(activityMapper, mock(ProductMapper.class),
                mock(RedisCacheClient.class), cacheEvictService, stockService);
    }

    private FlashActivity activity(int totalStock, int availableStock) {
        FlashActivity activity = new FlashActivity();
        activity.setId(9001L);
        activity.setProductId(9001L);
        activity.setMerchantId(9001L);
        activity.setTotalStock(totalStock);
        activity.setAvailableStock(availableStock);
        activity.setFlashPrice(39900);
        activity.setStartTime(LocalDateTime.now().minusHours(1));
        activity.setEndTime(LocalDateTime.now().plusDays(1));
        activity.setStatus(FlashActivity.STATUS_ONLINE);
        return activity;
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    @DisplayName("改小总库存：可用库存 = 新总库存 − 已售（原 100/剩 37 → 改 80 后剩 17）")
    void updateKeepsSoldAmount() {
        UserContext.set(new UserContext.LoginUser(9001L, "MERCHANT", "127.0.0.1"));
        when(activityMapper.selectById(9001L)).thenReturn(activity(100, 37));

        service().update(9001L, 80, LocalDateTime.now(), LocalDateTime.now().plusDays(1));

        ArgumentCaptor<FlashActivity> captor = ArgumentCaptor.forClass(FlashActivity.class);
        verify(activityMapper).updateById(captor.capture());
        FlashActivity saved = captor.getValue();
        assertEquals(80, saved.getTotalStock());
        // 已售 = 100 − 37 = 63，所以新可用 = 80 − 63 = 17（而不是保持 37）
        assertEquals(17, saved.getAvailableStock());
        verify(stockService).resetStock(9001L, 17, 80);
    }

    @Test
    @DisplayName("总库存改到小于已售：直接拒绝，且不更新数据库、不动 Redis")
    void rejectWhenTotalBelowSold() {
        UserContext.set(new UserContext.LoginUser(9001L, "MERCHANT", "127.0.0.1"));
        when(activityMapper.selectById(9001L)).thenReturn(activity(100, 37)); // 已售 63

        BizException error = assertThrows(BizException.class,
                () -> service().update(9001L, 50, LocalDateTime.now(), LocalDateTime.now().plusDays(1)));

        assertTrue(error.getMessage().contains("已售出的 63 件"));
        verify(activityMapper, never()).updateById(any(FlashActivity.class));
        verify(stockService, never()).resetStock(anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("不是自己的活动不能改")
    void rejectOtherMerchant() {
        UserContext.set(new UserContext.LoginUser(9002L, "MERCHANT", "127.0.0.1"));
        when(activityMapper.selectById(9001L)).thenReturn(activity(100, 37));
        BizException error = assertThrows(BizException.class,
                () -> service().update(9001L, 80, LocalDateTime.now(), LocalDateTime.now().plusDays(1)));
        assertEquals(403, error.getCode());
    }
}
