package com.lrs.buddy.framework.config;

import com.lrs.buddy.framework.security.AccessDeniedHandlerImpl;
import com.lrs.buddy.framework.security.AuthenticationEntryPointImpl;
import com.lrs.buddy.framework.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Spring Security 配置。
 *
 * <p>三条关键决策：
 * <ol>
 *   <li><b>无状态</b>：{@code SessionCreationPolicy.STATELESS}，服务端不创建 HttpSession，
 *       便于水平扩展；用户状态全部来自 JWT</li>
 *   <li><b>关闭 CSRF</b>：CSRF 攻击依赖浏览器自动携带 Cookie，而这里用
 *       Authorization 头传递令牌，且无 Cookie 会话，不具备攻击条件</li>
 *   <li><b>Actuator 独立过滤链</b>：端点可能暴露环境变量等敏感信息，单独一条链要求
 *       管理员角色，与主业务链的放行策略互不干扰</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthenticationEntryPointImpl authenticationEntryPoint;
    private final AccessDeniedHandlerImpl accessDeniedHandler;
    private final CorsConfigurationSource corsConfigurationSource;

    /**
     * Actuator 端点链：order 值更小，优先匹配。
     *
     * <p>health/info 放行给监控系统（容器探针、负载均衡健康检查无需令牌），
     * 其余端点（prometheus/metrics/loggers 等）必须管理员角色。
     *
     * <p>本链必须自行装配 {@link JwtAuthenticationFilter}：Security 的多条过滤链彼此独立，
     * 主业务链（Order 2）里加的 JWT 过滤器不会作用于本链。若此处遗漏，令牌根本不会被解析，
     * {@code hasRole("ADMIN")} 永远不成立——即便持有效管理员令牌也会被挡在 401，
     * 使 Prometheus 指标实际无法抓取（可观测性端点形同虚设）。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("health", "info")).permitAll()
                        .anyRequest().hasRole("ADMIN"))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 主业务链（后台）。
     *
     * <p>@Order(3)：必须排在 C 端链（{@code MemberSecurityConfig} 的 @Order(2)）之后。
     * 两条链靠 securityMatcher 的匹配集互斥分流，但 Spring Security 是**按 order 逐条尝试**的：
     * 若本链在前，它会把全部请求（包含 /member/**）都吃进 anyRequest().authenticated()，
     * 于是 C 端令牌在后台密钥下验不过、返回 401 —— 现场看起来像“C 端登录一直失败”。
     */
    @Bean
    @Order(3)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 登录与接口文档放行（项目统一上下文路径为 /api）
                        .requestMatchers("/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // JWT 过滤器放在表单登录过滤器之前，接管所有请求的鉴权
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 密码加密器。
     *
     * <p>BCrypt 自带随机盐，相同明文每次加密结果都不同，
     * 且计算刻意偏慢，能显著增加暴力破解成本。
     * 强度 10 是安全性与响应耗时的常用折中。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
