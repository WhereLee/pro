package com.lrs.buddy.biz.barrier.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrs.buddy.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 计划点全生命周期：建 → 改 → 停用 → 删，含重复时刻校验与"改/删不存在"的失败路径。
 * 单一测试方法内按序执行，避免用例间对共享 H2 的顺序依赖。走框架 JWT + RBAC，超级管理员令牌。
 */
class BarrierScheduleLifecycleTest extends AbstractIntegrationTest {

    private long create(String json) throws Exception {
        MvcResult r = mockMvc.perform(post("/barrier/schedules")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("data").asLong();
    }

    @Test
    @DisplayName("建→改→重复被拒→停用→同刻可再建→删→列表已无")
    void fullLifecycle() throws Exception {
        long id = create("{\"timeOfDay\":\"07:00:00\",\"planState\":\"OPEN\",\"name\":\"lifecycle\"}");

        // 改：07:00 → 07:30，OPEN→CLOSE
        mockMvc.perform(put("/barrier/schedules/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeOfDay\":\"07:30:00\",\"planState\":\"CLOSE\",\"name\":\"lifecycle\"}"))
                .andExpect(jsonPath("$.code").value(200));

        // 重复：再来一个 07:30 的启用点 → 被拒（业务异常 500）
        mockMvc.perform(post("/barrier/schedules")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeOfDay\":\"07:30:00\",\"planState\":\"OPEN\"}"))
                .andExpect(jsonPath("$.code").value(500));

        // 停用后，同刻不再算重复 → 可建
        mockMvc.perform(put("/barrier/schedules/" + id + "/enabled")
                        .header("Authorization", "Bearer " + adminToken).param("enabled", "0"))
                .andExpect(jsonPath("$.code").value(200));
        long id2 = create("{\"timeOfDay\":\"07:30:00\",\"planState\":\"OPEN\"}");

        // 删（逻辑）
        mockMvc.perform(delete("/barrier/schedules/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/barrier/schedules/" + id2).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));

        // 列表里不应再包含被删的两个 id
        MvcResult list = mockMvc.perform(get("/barrier/schedules").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = objectMapper.readTree(list.getResponse().getContentAsString()).path("data");
        for (JsonNode n : data) {
            long cur = n.path("id").asLong();
            org.assertj.core.api.Assertions.assertThat(cur).isNotIn(id, id2);
        }
    }

    @Test
    @DisplayName("改/删不存在的计划点 → 业务异常")
    void notFound() throws Exception {
        mockMvc.perform(put("/barrier/schedules/999999999")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeOfDay\":\"05:00:00\",\"planState\":\"OPEN\"}"))
                .andExpect(jsonPath("$.code").value(500));
        mockMvc.perform(delete("/barrier/schedules/999999999").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(500));
    }
}
