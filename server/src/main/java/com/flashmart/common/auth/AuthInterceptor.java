package com.flashmart.common.auth;

import com.flashmart.common.context.UserContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * 登录拦截器：解析 JWT → 校验 Redis 会话 → 写入 UserContext。
 * Redis 会话让服务端可以主动失效（改密码、封号后旧令牌立刻作废），并在每次请求时滑动续期。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String SESSION_KEY_PREFIX = "session:token:";

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = resolveToken(request);
        if (token == null) {
            return reject(response, "未登录或登录已过期");
        }
        try {
            Claims claims = jwtUtil.parse(token);
            String sessionKey = SESSION_KEY_PREFIX + claims.getSubject();
            if (Boolean.FALSE.equals(redis.hasKey(sessionKey))) {
                return reject(response, "登录已失效，请重新登录");
            }
            redis.expire(sessionKey, 24, TimeUnit.HOURS);
            UserContext.set(new UserContext.LoginUser(
                    Long.valueOf(claims.getSubject()),
                    String.valueOf(claims.get("role")),
                    clientIp(request)));
            return true;
        } catch (Exception e) {
            return reject(response, "token 无效");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private boolean reject(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\"}");
        return false;
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip.split(",")[0].trim();
    }
}
