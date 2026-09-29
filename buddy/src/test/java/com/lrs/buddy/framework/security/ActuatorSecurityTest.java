package com.lrs.buddy.framework.security;

import com.lrs.buddy.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator 端点安全回归测试。
 *
 * <p>锁定 {@code SecurityConfig} 两条独立过滤链的语义：
 * <ul>
 *   <li>health/info 对监控探针匿名放行（容器 HEALTHCHECK、负载均衡健康检查不带令牌）；</li>
 *   <li>prometheus/metrics 等其余端点必须管理员角色，且该角色判定依赖 actuator 链
 *       <b>自行装配</b> {@code JwtAuthenticationFilter}。历史上此链遗漏了 JWT 过滤器，
 *       导致即便持有效管理员令牌也被挡在 401、Prometheus 指标实际无法抓取；本测试防止该回归。</li>
 * </ul>
 */
class ActuatorSecurityTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("health 端点匿名可访问：供容器探针/负载均衡健康检查")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("metrics 端点匿名访问被拒（401）：角色门禁生效")
    void metricsRejectsAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("metrics 端点持管理员令牌可访问（200）：证明 actuator 链已装配 JWT 过滤器")
    void metricsAccessibleWithAdminToken() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
