package com.lrs.buddy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quartz 动态任务的创建/分页/可用执行体枚举集成测试（cron 设为 2099 避免真触发）。
 */
class JobApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("新增任务 → 分页可查 → beans 列表含 demoTask")
    void create_page_beans() throws Exception {
        String body = "{\"jobName\":\"IT任务_" + System.currentTimeMillis() + "\","
                + "\"jobGroup\":\"DEFAULT\",\"beanName\":\"demoTask\","
                + "\"cronExpression\":\"0 0 0 1 1 ? 2099\",\"status\":0,\"concurrent\":1,\"misfirePolicy\":1}";

        mockMvc.perform(post("/sys/job")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/sys/job/page")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNum\":1,\"pageSize\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());

        MvcResult beans = mockMvc.perform(get("/sys/job/beans")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();
        assertTrue(beans.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("demoTask"),
                "可用执行体应包含 demoTask");
    }

    @Test
    @DisplayName("任务名/执行体为空 → 业务异常 → HTTP 200 + code 500")
    void blank_job_rejected() throws Exception {
        mockMvc.perform(post("/sys/job")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobName\":\"\",\"beanName\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }
}
