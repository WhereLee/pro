package com.lrs.buddy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MySQL 一致性集成测试（真库，非 H2）。
 *
 * <p>验证两类"只有真 MySQL 才暴露"的回归：
 * <ol>
 *   <li>utf8mb4 + UTF-8 脚本导入后，中文种子数据不乱码（编码基线的生产等价证明）</li>
 *   <li>MyBatis 自定义 SQL（LEFT JOIN + CONCAT + LIMIT 分页）在 MySQL 方言下正常执行</li>
 * </ol>
 *
 * <p>需外部 MySQL，故打 {@code @Tag("mysql")}，由 surefire 默认排除；
 * CI 的 mysql-consistency job 用 {@code -Dsurefire.excludedGroups=} 反选运行。
 */
@Tag("mysql")
@ActiveProfiles("mysql-it")
class MysqlConsistencyTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("真实 MySQL 上超级管理员可登录并签发令牌")
    void admin_login_on_mysql() {
        assertNotNull(adminToken);
    }

    @Test
    @DisplayName("菜单中文种子在 utf8mb4 下不乱码：首屏目录名为『系统管理』")
    void menu_seed_chinese_not_mojibake() throws Exception {
        mockMvc.perform(get("/auth/routes").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].menuName").value("系统管理"));
    }

    @Test
    @DisplayName("自定义 SQL 分页(selectUserPage, 含 LEFT JOIN/CONCAT/LIMIT)在 MySQL 下可用")
    void custom_sql_page_works() throws Exception {
        mockMvc.perform(post("/sys/user/page")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNum\":1,\"pageSize\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").isNumber());
    }
}
