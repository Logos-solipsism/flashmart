package com.flashmart.modules.agent.controller;

import com.flashmart.common.exception.GlobalExceptionHandler;
import com.flashmart.modules.agent.service.AgentQueryService;
import com.flashmart.modules.agent.vo.AgentActivityVO;
import com.flashmart.modules.agent.vo.AgentOrderVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口契约：三条路由都是 GET、都用既有 R 包一层、参数按名字绑定。
 *
 * <p>这里用 standaloneSetup，只装这一个控制器 —— 鉴权由 AgentAuthInterceptorTest 单独钉住，
 * 两类断言各管各的，谁挂了一眼就能看出是哪一层的问题。
 */
class AgentControllerTest {

    private final AgentQueryService service = mock(AgentQueryService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AgentController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("GET /api/agent/health -> R<{ok:true}>，code=0")
    void healthReturnsOk() throws Exception {
        mockMvc.perform(get("/api/agent/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ok").value(true));
    }

    @Test
    @DisplayName("GET /api/agent/activities -> R<List>，keyword 与 limit 都透传到服务层")
    void activitiesBindParams() throws Exception {
        when(service.activities(any(), anyInt())).thenReturn(List.of(
                new AgentActivityVO(11L, "活动-11", "商品-11", 39900, 59900, 100, 7,
                        "2026-01-01T10:00:00", "2026-01-01T12:00:00", 1)));

        mockMvc.perform(get("/api/agent/activities").param("keyword", "秒杀").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(11))
                .andExpect(jsonPath("$.data[0].remainingStock").value(7));

        verify(service).activities(eq("秒杀"), eq(5));
    }

    @Test
    @DisplayName("GET /api/agent/activities 不传 limit -> 用默认值 20，不是 0")
    void activitiesDefaultLimit() throws Exception {
        when(service.activities(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/agent/activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(service).activities(eq(null), eq(20));
    }

    @Test
    @DisplayName("GET /api/agent/orders -> R<List>，userId 与 orderNo 都透传")
    void ordersBindParams() throws Exception {
        when(service.orders(any(), any())).thenReturn(List.of(
                new AgentOrderVO("FM1", 9003L, 11L, "活动-11", "商品-11", 39900, "PENDING_PAY", null,
                        "2026-01-01T10:00:00", "")));

        mockMvc.perform(get("/api/agent/orders").param("userId", "9003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].orderNo").value("FM1"));

        verify(service).orders(eq(9003L), eq(null));
    }

    /**
     * 只读契约：三条路由都没有写方法。
     *
     * <p>断言看的是响应体里的 code 而不是 HTTP 状态码：项目的 GlobalExceptionHandler 把
     * 未映射请求也收进了 handleOther，统一回 HTTP 200 + code=500 —— 这是既有行为，
     * 不该为了这条测试去动它。code=500 已经足以证明「POST 走的不是这些接口」。
     */
    @Test
    @DisplayName("只读契约：/api/agent/orders、/activities、/health 都不接受 POST")
    void writesAreNotMapped() throws Exception {
        mockMvc.perform(post("/api/agent/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
        mockMvc.perform(post("/api/agent/activities"))
                .andExpect(jsonPath("$.code").value(500));
        mockMvc.perform(post("/api/agent/health"))
                .andExpect(jsonPath("$.code").value(500));
    }
}
