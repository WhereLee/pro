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

/** 策略 CRUD 生命周期：建→改→停用→删；删"仍挂计划点的策略"被拒。走框架 JWT + RBAC，超级管理员令牌。 */
class StrategyApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("建→改→停用→删（无计划点的策略可删）")
    void crudLifecycle() throws Exception {
        MvcResult r = mockMvc.perform(post("/barrier/strategies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"临时策略\",\"description\":\"t\",\"priority\":50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn();
        long id = objectMapper.readTree(r.getResponse().getContentAsString()).path("data").asLong();
        assertThat(id).isPositive();

        mockMvc.perform(put("/barrier/strategies/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"临时策略2\",\"priority\":60}"))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(put("/barrier/strategies/" + id + "/enabled")
                        .header("Authorization", "Bearer " + adminToken).param("enabled", "0"))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/barrier/strategies/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));

        MvcResult list = mockMvc.perform(get("/barrier/strategies").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = objectMapper.readTree(list.getResponse().getContentAsString()).path("data");
        for (JsonNode n : data) {
            assertThat(n.path("id").asLong()).isNotEqualTo(id);
        }
    }

    @Test
    @DisplayName("删除仍挂计划点的策略（种子策略1）→ 被拒")
    void deleteWithPointsRejected() throws Exception {
        mockMvc.perform(delete("/barrier/strategies/1").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(500));
    }
}
