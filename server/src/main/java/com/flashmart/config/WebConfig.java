package com.flashmart.config;

import com.flashmart.common.auth.AuthInterceptor;
import com.flashmart.modules.agent.auth.AgentAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AgentAuthInterceptor agentAuthInterceptor;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/agent/**", "/doc.html", "/webjars/**", "/v3/api-docs/**");
        // 外部 Agent 服务走独立的服务令牌，不进 JWT 链路：鉴权失败一律 401，未配置令牌时 fail-closed
        registry.addInterceptor(agentAuthInterceptor)
                .addPathPatterns("/api/agent/**");
    }
}
