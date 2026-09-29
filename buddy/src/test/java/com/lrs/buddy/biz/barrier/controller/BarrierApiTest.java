package com.lrs.buddy.biz.barrier.controller;

import com.lrs.buddy.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 升降杆样例运行面接口集成测试（并入框架后走 buddy 的 JWT + RBAC 全链路）。
 *
 * <p>与框架其它 *ApiTest 一致：继承 {@link AbstractIntegrationTest}，用超级管理员真实登录拿
 * {@code adminToken}（superAdmin 经 selectPermsByUserId 自动获得含 barrier:* 的全部权限），
 * 每个请求头带 Bearer 令牌；断言"结构化双层契约"（HTTP 状态 + 响应体 code/success/data）。
 * context-path(/api) 在 MockMvc 不生效，故路径不带 /api。
 */
class BarrierApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("查询当前态：R 双层契约结构完整")
    void statusShape() throws Exception {
        mockMvc.perform(get("/barriers/1/status").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.state").exists());
    }

    @Test
    @DisplayName("手动 CLOSE → 生效，查询为 CLOSED")
    void manualClose() throws Exception {
        mockMvc.perform(post("/barriers/1/manual")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CLOSE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/barriers/1/status").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.state").value("CLOSED"))
                .andExpect(jsonPath("$.data.manualOverride").value(true));
    }

    @Test
    @DisplayName("非法 action → 业务异常 HTTP200 + code500")
    void manualInvalid() throws Exception {
        mockMvc.perform(post("/barriers/1/manual")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"NOPE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    @DisplayName("action 为空 → @Valid 400")
    void manualBlank() throws Exception {
        mockMvc.perform(post("/barriers/1/manual")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("计划点：种子存在，可新增")
    void schedules() throws Exception {
        mockMvc.perform(get("/barrier/schedules").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        mockMvc.perform(post("/barrier/schedules")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeOfDay\":\"06:30:00\",\"planState\":\"OPEN\",\"name\":\"测试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    @DisplayName("事件流为数组")
    void events() throws Exception {
        mockMvc.perform(get("/barriers/1/events").header("Authorization", "Bearer " + adminToken).param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
