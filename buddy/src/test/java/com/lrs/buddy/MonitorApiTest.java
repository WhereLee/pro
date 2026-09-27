package com.lrs.buddy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 系统监控集成测试：OSHI 指标快照 + 在线用户台账（Redis）。
 */
class MonitorApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("服务器指标快照含 cpu/mem/jvm 结构")
    void server_snapshot() throws Exception {
        MvcResult res = mockMvc.perform(get("/monitor/server")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.cpu").exists())
                .andExpect(jsonPath("$.data.mem").exists())
                .andExpect(jsonPath("$.data.jvm").exists())
                .andReturn();
        // 采样应给出运行时信息，非空壳
        assertTrue(res.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("jvm"));
    }

    @Test
    @DisplayName("在线用户列表为数组（当前管理员会话已在台账中）")
    void online_users() throws Exception {
        mockMvc.perform(get("/monitor/online")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }
}
