package com.flashmart.modules.agent;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.flashmart.common.auth.AuthInterceptor;
import com.flashmart.common.exception.GlobalExceptionHandler;
import com.flashmart.config.WebConfig;
import com.flashmart.modules.activity.entity.FlashActivity;
import com.flashmart.modules.activity.mapper.FlashActivityMapper;
import com.flashmart.modules.activity.service.ActivityService;
import com.flashmart.modules.agent.auth.AgentAuthInterceptor;
import com.flashmart.modules.order.entity.SeckillOrder;
import com.flashmart.modules.order.mapper.SeckillOrderMapper;
import com.flashmart.modules.product.entity.Product;
import com.flashmart.modules.product.mapper.ProductMapper;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 端到端：真的起一个 Tomcat，用真实 HTTP 请求打 /api/agent/**。
 *
 * <p>与 MockMvc 那几层的分工：MockMvc 证明「拦截器判断得对、路径挂得对」，
 * 这里证明「真实链路上客户端看到的东西是对的」—— 尤其是 401 是<b>真的 HTTP 401</b>，
 * 而不是被统一异常处理器包成 HTTP 200 + code=401。外部 Python 服务靠状态码判断放不放行，
 * 这一条必须用真端口验一次。
 *
 * <p>只加载 agent 模块与 WebConfig：MySQL / Redis / RocketMQ 都不在这台机器上，
 * 这组接口本身也不依赖它们（活动与订单的读取全部在 Mock 后面）。
 */
@SpringBootTest(
        classes = AgentHttpContractTest.AgentApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "flashmart.agent.service-token=probe-token-abc123")
class AgentHttpContractTest {

    private static final String TOKEN = "probe-token-abc123";

    @SpringBootApplication(scanBasePackages = "com.flashmart.modules.agent", exclude = {
            DataSourceAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            RocketMQAutoConfiguration.class})
    @Import({WebConfig.class, GlobalExceptionHandler.class})
    static class AgentApp {
    }

    @LocalServerPort
    int port;

    @MockBean
    AuthInterceptor authInterceptor;
    @MockBean
    ActivityService activityService;
    @MockBean
    FlashActivityMapper activityMapper;
    @MockBean
    SeckillOrderMapper orderMapper;
    @MockBean
    ProductMapper productMapper;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> call(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (token != null) {
            builder.header(AgentAuthInterceptor.TOKEN_HEADER, token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void stubData() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", 11L);
        item.put("title", "限时抢购-机械键盘");
        item.put("productTitle", "HHKB Pro 2");
        item.put("flashPrice", 39900);
        item.put("originPrice", 59900);
        item.put("totalStock", 100);
        item.put("remainingStock", 7);
        item.put("startTime", "2026-01-01T10:00:00");
        item.put("endTime", "2026-01-01T12:00:00");
        item.put("status", 1);
        when(activityService.listOnline(any())).thenReturn(List.of(item));

        SeckillOrder order = new SeckillOrder();
        order.setOrderNo("FM202601010000001");
        order.setUserId(9003L);
        order.setActivityId(11L);
        order.setProductId(22L);
        order.setFlashPrice(39900);
        order.setStatus(SeckillOrder.STATUS_PENDING_PAY);
        order.setCreateTime(LocalDateTime.of(2026, 1, 1, 10, 0, 0));
        when(orderMapper.selectList(any())).thenReturn(List.of(order));

        FlashActivity activity = new FlashActivity();
        activity.setId(11L);
        activity.setTitle("限时抢购-机械键盘");
        when(activityMapper.selectBatchIds(any())).thenReturn(List.of(activity));
        Product product = new Product();
        product.setId(22L);
        product.setTitle("HHKB Pro 2");
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(product));
    }

    @Test
    @DisplayName("GET /api/agent/health：200 + R<{ok:true}>")
    void healthThroughRealHttp() throws Exception {
        HttpResponse<String> response = call("/api/agent/health", TOKEN);

        assertEquals(200, response.statusCode());
        assertEquals("{\"code\":0,\"message\":\"success\",\"data\":{\"ok\":true}}", response.body());
    }

    @Test
    @DisplayName("GET /api/agent/activities：复用买家侧列表，返回契约要求的字段")
    void activitiesThroughRealHttp() throws Exception {
        stubData();
        HttpResponse<String> response = call("/api/agent/activities?keyword=&limit=5", TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("{\"code\":0,"));
        assertTrue(response.body().contains("\"remainingStock\":7"));
        assertTrue(response.body().contains("\"startTime\":\"2026-01-01T10:00:00\""));
    }

    @Test
    @DisplayName("GET /api/agent/orders：按 userId 查，返回订单快照")
    void ordersThroughRealHttp() throws Exception {
        stubData();
        HttpResponse<String> response = call("/api/agent/orders?userId=9003", TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"orderNo\":\"FM202601010000001\""));
        assertTrue(response.body().contains("\"status\":\"PENDING_PAY\""));
    }

    @Test
    @DisplayName("缺令牌是真正的 HTTP 401（不是被包成 200 + code=401）")
    void missingTokenIsRealHttp401() throws Exception {
        assertEquals(401, call("/api/agent/health", null).statusCode());
    }

    @Test
    @DisplayName("令牌错误同样是真正的 HTTP 401")
    void wrongTokenIsRealHttp401() throws Exception {
        assertEquals(401, call("/api/agent/health", "wrong-token").statusCode());
    }

    @Test
    @DisplayName("令牌正确时不需要 Authorization：/api/agent/** 不走用户 JWT 拦截器")
    void agentEndpointsDoNotRequireJwt() throws Exception {
        // 这里刻意不设 Authorization，也不让 AuthInterceptor 放行（Mock 默认返回 false）
        assertEquals(200, call("/api/agent/health", TOKEN).statusCode());
    }

    @Test
    @DisplayName("过滤条件全缺时是明确的业务错误，不是全表扫描")
    void ordersWithoutFilterIsABizError() throws Exception {
        HttpResponse<String> response = call("/api/agent/orders", TOKEN);

        // 既有 GlobalExceptionHandler 对 BizException 统一回 HTTP 200 + 业务码，这里沿用不另起炉灶
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"code\":400"));
    }
}
