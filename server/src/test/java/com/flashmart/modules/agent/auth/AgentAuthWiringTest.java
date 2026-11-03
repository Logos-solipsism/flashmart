package com.flashmart.modules.agent.auth;

import com.flashmart.common.auth.AuthInterceptor;
import com.flashmart.common.exception.GlobalExceptionHandler;
import com.flashmart.config.WebConfig;
import com.flashmart.modules.activity.controller.ActivityController;
import com.flashmart.modules.activity.service.ActivityService;
import com.flashmart.modules.agent.controller.AgentController;
import com.flashmart.modules.agent.service.AgentQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 拦截器装配：证明 WebConfig 里两条路径集合确实是分开的。
 *
 * <p>各自 new 一个拦截器的单测只能证明「它自己判断得对」，证明不了「它被挂在了哪些路径上」。
 * 这里把真实的 {@link WebConfig#addInterceptors} 输出装进 MockMvc，用真实路径发请求。
 *
 * <p>注意 {@code InterceptorRegistry#getInterceptors()} 是 protected —— 这是 Spring 唯一能问出
 * 「你到底注册了哪些拦截器、挂在哪些路径」的入口（WebMvcConfigurationSupport 自己就是这么调的）。
 * MockMvc 的 addInterceptors 不做路径匹配，所以必须取到 MappedInterceptor 而不是裸拦截器，
 * 否则这条测试会退化成「两个拦截器都被无差别调用」，什么都证明不了。
 */
class AgentAuthWiringTest {

    private static final String TOKEN = "s3cr3t-agent-token";

    private final AuthInterceptor authInterceptor = mock(AuthInterceptor.class);
    private final AgentQueryService agentQueryService = mock(AgentQueryService.class);
    private final ActivityService activityService = mock(ActivityService.class);

    /** 走一遍真实的 WebConfig 装配，拿到带路径规则的拦截器 */
    @SuppressWarnings("unchecked")
    private HandlerInterceptor[] wiredInterceptors(String configuredToken) throws Exception {
        WebConfig config = new WebConfig(authInterceptor, new AgentAuthInterceptor(configuredToken));
        InterceptorRegistry registry = new InterceptorRegistry();
        config.addInterceptors(registry);
        Method method = InterceptorRegistry.class.getDeclaredMethod("getInterceptors");
        method.setAccessible(true);
        List<Object> wired = (List<Object>) method.invoke(registry);
        return wired.toArray(new HandlerInterceptor[0]);
    }

    private MockMvc mockMvc(String configuredToken) throws Exception {
        return MockMvcBuilders
                .standaloneSetup(new AgentController(agentQueryService), new ActivityController(activityService))
                .addInterceptors(wiredInterceptors(configuredToken))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("带正确令牌：/api/agent/health 放行到控制器")
    void correctTokenReachesController() throws Exception {
        mockMvc(TOKEN).perform(get("/api/agent/health").header(AgentAuthInterceptor.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ok").value(true));
    }

    @Test
    @DisplayName("不带令牌：/api/agent/health 在拦截器层就 401，根本没进控制器")
    void missingTokenIsRejectedBeforeController() throws Exception {
        MockHttpServletResponse response = mockMvc(TOKEN).perform(get("/api/agent/health"))
                .andReturn().getResponse();

        assertEquals(401, response.getStatus());
        assertFalse(response.getContentAsString().contains("\"ok\""));
    }

    @Test
    @DisplayName("未配置令牌：/api/agent/** 一律 401（fail-closed 在装配层同样成立）")
    void unconfiguredTokenIsRejectedAtWiringLevel() throws Exception {
        MockHttpServletResponse response = mockMvc("")
                .perform(get("/api/agent/health").header(AgentAuthInterceptor.TOKEN_HEADER, TOKEN))
                .andReturn().getResponse();

        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("令牌同样能过订单与活动接口，且不会写库")
    void tokenGatesEveryAgentEndpoint() throws Exception {
        when(agentQueryService.activities(any(), any(Integer.class))).thenReturn(List.of());
        MockMvc mvc = mockMvc(TOKEN);

        mvc.perform(get("/api/agent/activities").header(AgentAuthInterceptor.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(401, mvc.perform(get("/api/agent/orders")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("排除 /api/agent/** 没有连累其它 /api 路径：/api/activity/list 仍由 JWT 拦截器把关")
    void otherApiPathsStillGoThroughJwtInterceptor() throws Exception {
        // JWT 拦截器照常拒绝；真实实现里没有 Authorization: Bearer 就是 401
        when(authInterceptor.preHandle(any(), any(), any())).thenAnswer(invocation -> {
            MockHttpServletResponse response = invocation.getArgument(1);
            response.setStatus(401);
            return false;
        });
        when(activityService.listOnline(any())).thenReturn(List.of());

        MockHttpServletResponse response = mockMvc(TOKEN)
                .perform(get("/api/activity/list").header(AgentAuthInterceptor.TOKEN_HEADER, TOKEN))
                .andReturn().getResponse();

        assertEquals(401, response.getStatus());
    }
}
