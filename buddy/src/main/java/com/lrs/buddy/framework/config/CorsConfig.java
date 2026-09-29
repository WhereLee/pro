package com.lrs.buddy.framework.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 跨域配置。
 *
 * <p>与 Spring Security 配合时必须把 {@code CorsConfigurationSource} 注册为 Bean，
 * 并在 Security 过滤器链里调用 {@code .cors(...)}，否则预检请求（OPTIONS）
 * 会先被鉴权过滤器拦截，导致浏览器报 CORS 错误。
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 明确列出来源而非使用 "*"：一旦允许携带凭证（Cookie/Authorization），
        // 通配符来源会被浏览器拒绝
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
