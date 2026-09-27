package com.lrs.buddy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通知公告的状态机与用户侧可见/已读集成测试（全体定向）。
 */
class NoticeApiTest extends AbstractIntegrationTest {

    private String auth() {
        return "Bearer " + adminToken;
    }

    private long createDraft(String title) throws Exception {
        String body = "{\"title\":\"" + title + "\",\"content\":\"hello\",\"type\":1,\"targetType\":1}";
        MvcResult res = mockMvc.perform(post("/notice")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").asLong();
    }

    @Test
    @DisplayName("新增默认草稿 → 发布 → 我可见列表包含该公告 → 标记已读")
    void publish_then_visible_then_read() throws Exception {
        String title = "公告测试_" + System.currentTimeMillis();
        long id = createDraft(title);
        assertTrue(id > 0);

        // 发布
        mockMvc.perform(put("/notice/publish/" + id).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 用户端可见列表应包含该公告（全体定向）
        MvcResult mine = mockMvc.perform(get("/notice/mine").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();
        JsonNode data = objectMapper.readTree(mine.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        boolean found = false;
        for (JsonNode node : data) {
            if (title.equals(node.path("title").asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "发布的全体公告应出现在 /notice/mine 中");

        // 标记已读 + 未读数为非负
        mockMvc.perform(put("/notice/read/" + id).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/notice/unread-count").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNumber());
    }

    @Test
    @DisplayName("标题为空 → @Valid 拦截 → HTTP 200 + code 400")
    void blank_title_rejected() throws Exception {
        mockMvc.perform(post("/notice")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"targetType\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
