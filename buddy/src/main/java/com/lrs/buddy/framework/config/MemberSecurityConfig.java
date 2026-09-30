package com.lrs.buddy.framework.config;

import com.lrs.buddy.biz.member.security.MemberJwtAuthenticationFilter;
import com.lrs.buddy.framework.security.AccessDeniedHandlerImpl;
import com.lrs.buddy.framework.security.AuthenticationEntryPointImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * C 端（member 域）独立过滤链。
 *
 * 为什么单独一条链而不是在主链里加几条 requestMatchers 判断：
 * 混在一条链里，鉴权规则就变成"路径白名单 + 令牌类型"两套条件的交叉，
 * 新增接口时很容易出现"以为限制了其实没限制"。两条链的匹配集合互斥，
 * 匹配不上的请求根本不会被这条链处理，规则是**可枚举**的。
 *
 * 顺序很关键：本链 @Order(2)，后台主链必须是 @Order(3)，否则 `/member/**`
 * 会先被主链的 anyRequest().authenticated() + 后台 JWT 过滤器接管，
 * C 端令牌在后台密钥下验不过 → 全部 401，症状看起来像"C 端登录失效"而不是"链配错了"。
 */
@Configuration
@RequiredArgsConstructor
public class MemberSecurityConfig {

    private final MemberJwtAuthenticationFilter memberJwtAuthenticationFilter;
    private final AuthenticationEntryPointImpl authenticationEntryPoint;
    private final AccessDeniedHandlerImpl accessDeniedHandler;

    @Bean
    @Order(2)
    public SecurityFilterChain memberFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/member/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 注册/登录/刷新与验证码：此时还没有令牌
                        .requestMatchers("/member/auth/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(memberJwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
