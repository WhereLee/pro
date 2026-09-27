package com.lrs.buddy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类：真实启动 Spring 上下文 + H2 + Redis（CI service / 本地）。
 *
 * <p>用 MockMvc 打真实 HTTP 接口（走完整 Security 过滤器链、AOP、MyBatis、全局异常处理），
 * 相当于把 validate_l1.ps1 的黑盒冒烟固化进 CI——断言的是"结构化双层契约"
 * （HTTP 状态 + 响应体 code/success/data），而不只是 HTTP 200。
 *
 * <p>注意：应用配置了 {@code server.servlet.context-path=/api}，MockMvc 不走 Servlet 容器，
 * 因此所有请求路径都要手写 {@code /api} 前缀。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    /** 每个用例前用超级管理员真实登录，拿到带权限的 JWT 令牌。 */
    protected String adminToken;

    protected String login(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", username, "password", password));
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return json.path("data").path("token").asText(null);
    }

    @BeforeEach
    void authenticate() throws Exception {
        adminToken = login("admin", "Admin@123456");
    }
}
