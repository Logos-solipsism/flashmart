package com.flashmart.modules.agent.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 外部 Agent 服务专用鉴权：只认请求头 {@code X-Agent-Token}，与 {@code flashmart.agent.service-token} 比较。
 *
 * <p>与用户 JWT 拦截器是两套身份、互不重叠：Agent 是「服务身份」，没有用户会话，
 * 也就不该使用 Authorization 走 JWT + Redis 会话那条链路。WebConfig 里两个拦截器的路径集合被刻意分开。
 *
 * <p><b>未配置令牌时一律 401（fail-closed）</b>：宁可这组接口整体不可用，也不要在漏配一个环境变量时
 * 把活动与订单数据对外开放。这与 JwtUtil「密钥不配就启动失败」是同一个取向 —— 配置缺失是显式的，不是隐形的。
 */
@Component
public class AgentAuthInterceptor implements HandlerInterceptor {

    public static final String TOKEN_HEADER = "X-Agent-Token";

    private final String serviceToken;

    public AgentAuthInterceptor(@Value("${flashmart.agent.service-token:}") String serviceToken) {
        this.serviceToken = serviceToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (serviceToken == null || serviceToken.isBlank()) {
            return reject(response, "agent 接口未启用：未配置 flashmart.agent.service-token");
        }
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided == null || provided.isBlank()) {
            return reject(response, "缺少 " + TOKEN_HEADER);
        }
        if (!constantTimeEquals(provided, serviceToken)) {
            return reject(response, TOKEN_HEADER + " 无效");
        }
        return true;
    }

    /**
     * 常量时间比较：先各自 SHA-256 再比摘要。
     *
     * <p>直接比字符串会在第一个不同的字节处提前返回，比较耗时随「猜对了几位前缀」变化，
     * 攻击者可以从响应时间上逐位试出令牌。先摘要成定长，比较长度信息也就不再泄露。
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(sha256(a), sha256(b));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须提供 SHA-256；走到这里说明运行环境本身有问题，不能降级成明文比较
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 响应体沿用既有拦截器的 {"code":401,"message":"..."} 形状，调用方不用为两套 401 写两种解析 */
    private boolean reject(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\"}");
        return false;
    }
}
