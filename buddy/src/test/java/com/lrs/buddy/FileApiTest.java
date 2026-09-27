package com.lrs.buddy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件上传/下载集成测试：走 StorageService(本地实现) + 落库 + 按 ID 下载回读。
 */
class FileApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("上传 txt → 落库返回 id → 按 id 下载内容一致")
    void upload_then_download_roundtrip() throws Exception {
        byte[] payload = "buddy-file-it".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "it.txt", "text/plain", payload);

        MvcResult res = mockMvc.perform(multipart("/sys/file/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").exists())
                .andReturn();

        JsonNode data = objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        long id = data.path("id").asLong();
        assertTrue(id > 0);

        MvcResult dl = mockMvc.perform(get("/sys/file/download/" + id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertArrayEquals(payload, dl.getResponse().getContentAsByteArray(), "下载内容应与上传一致");
    }

    @Test
    @DisplayName("上传被允许类型外的扩展名 → 拒绝（非 200 业务码）")
    void reject_disallowed_extension() throws Exception {
        MockMultipartFile evil = new MockMultipartFile("file", "shell.exe",
                "application/octet-stream", "x".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/sys/file/upload")
                        .file(evil)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }
}
