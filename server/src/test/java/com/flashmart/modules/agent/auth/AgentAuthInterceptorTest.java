package com.flashmart.modules.agent.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 钉住 /api/agent/** 的三条鉴权语义：正确放行、错误 401、未配置令牌时一律 401（fail-closed）。 */
class AgentAuthInterceptorTest {

    private static final String CONFIGURED = "s3cr3t-agent-token";

    private final AgentAuthInterceptor interceptor = new AgentAuthInterceptor(CONFIGURED);

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader(AgentAuthInterceptor.TOKEN_HEADER, token);
        }
        return request;
    }

    @Test
    @DisplayName("令牌正确：放行")
    void correctTokenPasses() throws Exception {
        assertTrue(interceptor.preHandle(requestWithToken(CONFIGURED), new MockHttpServletResponse(), new Object()));
    }

    @Test
    @DisplayName("令牌错误：401")
    void wrongTokenIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(requestWithToken("not-the-token"), response, new Object()));

        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("缺少请求头：401（未配置令牌是另一条分支，这里配置是有的）")
    void missingHeaderIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(requestWithToken(null), response, new Object()));

        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("未配置令牌：即使请求头带了值也一律 401（fail-closed）")
    void unconfiguredTokenFailsClosed() throws Exception {
        AgentAuthInterceptor unconfigured = new AgentAuthInterceptor("");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(unconfigured.preHandle(requestWithToken(""), response, new Object()));
        assertEquals(401, response.getStatus());

        // 关键：这里"猜中"是可能的——空配置下客户端提交空值也必须被拒，不能变成"空对空就放行"
        MockHttpServletResponse anyGuess = new MockHttpServletResponse();
        assertFalse(unconfigured.preHandle(requestWithToken("anything"), anyGuess, new Object()));
        assertEquals(401, anyGuess.getStatus());
    }

    @Test
    @DisplayName("未配置令牌且模型给的是 null（YAML 里没写这一项）：同样 401")
    void nullTokenFailsClosed() throws Exception {
        AgentAuthInterceptor unconfigured = new AgentAuthInterceptor(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(unconfigured.preHandle(requestWithToken(CONFIGURED), response, new Object()));

        assertEquals(401, response.getStatus());
    }
}
