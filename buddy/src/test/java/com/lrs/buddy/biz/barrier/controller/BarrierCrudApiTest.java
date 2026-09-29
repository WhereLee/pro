package com.lrs.buddy.biz.barrier.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrs.buddy.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 杆 CRUD + 策略绑杆（N:M）。走框架 JWT + RBAC，超级管理员令牌。 */
class BarrierCrudApiTest extends AbstractIntegrationTest {

    private long createBarrier(String json) throws Exception {
        MvcResult r = mockMvc.perform(post("/barriers")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("data").asLong();
    }

    @Test
    @DisplayName("杆：建→列→改→停用→删（无绑定的杆可删）")
    void barrierCrudLifecycle() throws Exception {
        long id = createBarrier("{\"name\":\"2号杆\",\"location\":\"东门\"}");

        MvcResult list = mockMvc.perform(get("/barriers").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn();
        boolean found = false;
        for (JsonNode n : objectMapper.readTree(list.getResponse().getContentAsString()).path("data")) {
            if (n.path("id").asLong() == id) {
                found = true;
            }
        }
        assertThat(found).isTrue();

        mockMvc.perform(put("/barriers/" + id).header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"2号杆改\",\"location\":\"东门\"}")).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(put("/barriers/" + id + "/enabled").header("Authorization", "Bearer " + adminToken)
                .param("enabled", "0")).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/barriers/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("删除仍被策略绑定的杆（种子杆1）→ 被拒")
    void deleteBoundBarrierRejected() throws Exception {
        mockMvc.perform(delete("/barriers/1").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    @DisplayName("策略绑杆：整体替换绑定，可查回")
    void bindStrategyToBarriers() throws Exception {
        long bid = createBarrier("{\"name\":\"3号杆\"}");
        // 新建一个策略，避免动到种子策略1的绑定
        MvcResult sr = mockMvc.perform(post("/barrier/strategies").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"绑定测试策略\",\"priority\":10}"))
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        long sid = objectMapper.readTree(sr.getResponse().getContentAsString()).path("data").asLong();

        mockMvc.perform(put("/barrier/strategies/" + sid + "/barriers").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barrierIds\":[1," + bid + "]}"))
                .andExpect(jsonPath("$.code").value(200));

        MvcResult g = mockMvc.perform(get("/barrier/strategies/" + sid + "/barriers")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode ids = objectMapper.readTree(g.getResponse().getContentAsString()).path("data");
        assertThat(ids.toString()).contains(String.valueOf(bid));

        // 清理：解绑后删杆、删策略
        mockMvc.perform(put("/barrier/strategies/" + sid + "/barriers").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"barrierIds\":[]}")).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/barriers/" + bid).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/barrier/strategies/" + sid).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));
    }
}
