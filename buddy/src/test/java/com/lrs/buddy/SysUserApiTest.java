package com.lrs.buddy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户/部门等受 {@code @PreAuthorize} 保护的业务接口集成测试：分页契约、方法级鉴权、防重复提交。
 */
class SysUserApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("用户分页：结构 PageResult{records,total}")
    void user_page_ok() throws Exception {
        mockMvc.perform(post("/sys/user/page")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNum\":1,\"pageSize\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").isNumber());
    }

    @Test
    @DisplayName("部门树：管理员权限放行且返回数组")
    void dept_tree_ok() throws Exception {
        mockMvc.perform(get("/sys/dept/tree")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("无令牌访问用户分页 → 401")
    void user_page_without_token_is_401() throws Exception {
        mockMvc.perform(post("/sys/user/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNum\":1,\"pageSize\":10}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    @DisplayName("已认证但缺 sys:user:list 权限 → 方法级鉴权 403")
    void no_permission_forbidden() throws Exception {
        // 建一个不带任何角色的用户 → 登录得到"已认证、无权限"的令牌 → 访问受控接口应 403
        String unique = "noperm_" + System.currentTimeMillis();
        String createBody = "{\"username\":\"" + unique + "\",\"password\":\"Test@123456\","
                + "\"nickname\":\"noperm\",\"deptId\":100,\"status\":0,\"roleIds\":[]}";
        mockMvc.perform(post("/sys/user")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        String token = login(unique, "Test@123456");
        mockMvc.perform(post("/sys/user/page")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNum\":1,\"pageSize\":10}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @Transactional
    @DisplayName("@RepeatSubmit：间隔内重复提交同一新增 → 第二次 code 1404")
    void repeat_submit_blocked() throws Exception {
        String unique = "rt_" + System.currentTimeMillis();
        String body = "{\"username\":\"" + unique + "\",\"password\":\"Test@123456\","
                + "\"nickname\":\"repeat-test\",\"deptId\":100,\"status\":0,\"roleIds\":[1]}";
        mockMvc.perform(post("/sys/user")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/sys/user")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1404));
    }
}
