package com.lrs.buddy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证与鉴权链路的集成测试。
 *
 * <p>重点验证 buddy 的"双层契约"约定：业务错误走 HTTP 200 + body.code，
 * 只有认证/授权（发生在 Security 过滤器链）才用真实 HTTP 401/403。
 */
class AuthApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("登录成功：HTTP 200 + code 200 + 返回令牌")
    void login_success() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    @DisplayName("密码错误：业务异常 → HTTP 200 + code 500（不区分账号/密码错）")
    void login_wrong_password() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"definitely-wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("参数校验：空用户名 → @Valid 拦截 → HTTP 200 + code 400")
    void login_blank_username() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"whatever\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("未携带令牌访问受保护接口 → 真实 HTTP 401 + code 401")
    void protected_without_token_is_401() throws Exception {
        mockMvc.perform(get("/auth/info"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("非法令牌 → HTTP 401 + code 1402（TOKEN_INVALID）")
    void invalid_token_is_401() throws Exception {
        mockMvc.perform(get("/auth/info").header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1402));
    }

    @Test
    @DisplayName("携带管理员令牌获取当前用户信息")
    void info_with_token() throws Exception {
        mockMvc.perform(get("/auth/info").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.superAdmin").value(true));
    }

    @Test
    @DisplayName("携带管理员令牌获取动态路由（菜单树非空、含 menuName）")
    void routes_with_token() throws Exception {
        mockMvc.perform(get("/auth/routes").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].menuName").exists());
    }
}
