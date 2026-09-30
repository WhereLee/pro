package com.lrs.buddy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C 端身份域（member realm）集成测试。
 *
 * 这里最关键的一组断言不是"能不能登录"，而是**吊销到底有没有生效**：
 * 只把库里会话置成 REVOKED 是不够的 —— 已签发的 access 令牌在自然过期前仍然可用，
 * 除非过滤器每请求校验会话族。所以"复用检测后旧 access 必须立刻 401"是必测的一条。
 *
 * 双层契约沿用 buddy 约定：业务失败是 HTTP 200 + code!=200；
 * 只有认证/授权（发生在 Security 链上）才用真实 HTTP 401/403。
 */
class MemberAuthTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    private com.lrs.buddy.biz.member.service.MemberAuthService auth;

    private static String phone(String tag) {
        return "1390" + String.format("%06d", Math.abs(tag.hashCode()) % 1_000_000) + "0";
    }

    /** 发码并回读（测试 profile 开了 mock-sms-echo-code）。 */
    private String sendCode(String phone, String purpose) throws Exception {
        MvcResult result = mockMvc.perform(post("/member/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"purpose\":\"" + purpose + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode data = body(result);
        // MOCK 通道才回显：接真实短信后这里必须靠库里哈希验证，测试也要跟着改
        return data.path("echoCode").asText(null);
    }

    private JsonNode login(String phone, String code, String deviceType) throws Exception {
        MvcResult result = mockMvc.perform(post("/member/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code
                                + "\",\"deviceType\":\"" + deviceType + "\",\"deviceFingerprint\":\"fp-"
                                + deviceType + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(json.path("code").asInt())
                .as("登录失败，完整响应：" + result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return json.path("data");
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private int codeOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").asInt();
    }

    @Test
    @DisplayName("手机号+验证码注册即登录，access 可访问 /member/me，手机号只回脱敏值")
    void loginThenMe() throws Exception {
        String phone = phone("login-me");
        String code = sendCode(phone, "LOGIN");
        assertThat(code).as("MOCK 通道应回显验证码").isNotNull();

        JsonNode tokens = login(phone, code, "H5");
        assertThat(tokens.path("accessToken").asText()).isNotBlank();
        assertThat(tokens.path("refreshToken").asText()).isNotBlank();
        assertThat(tokens.path("sessionFamily").asText()).isNotBlank();

        mockMvc.perform(get("/member/me").header("Authorization", "Bearer " + tokens.path("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.realnameState").value("NONE"))
                .andExpect(jsonPath("$.data.maskPhone").value(org.hamcrest.Matchers.containsString("****")));

        Integer stored = jdbc.queryForObject("SELECT COUNT(*) FROM member_user WHERE phone_hash = ? "
                + "AND phone_cipher IS NOT NULL", Integer.class,
                jdbc.queryForObject("SELECT phone_hash FROM member_user WHERE id = ?", String.class,
                        tokens.path("memberId").asLong()));
        assertThat(stored).as("手机号必须同时有哈希与密文，不能存明文").isEqualTo(1);
    }

    @Test
    @DisplayName("验证码是一次性的：同一码第二次使用必须失败")
    void smsCodeIsSingleUse() throws Exception {
        String phone = phone("single-use");
        String code = sendCode(phone, "LOGIN");
        long memberId = login(phone, code, "H5").path("memberId").asLong();

        MvcResult second = mockMvc.perform(post("/member/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\",\"deviceType\":\"H5\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(codeOf(second)).as("验证码已被 CONSUMED，不能用第二次").isNotEqualTo(200);
        Integer families = jdbc.queryForObject("SELECT COUNT(DISTINCT session_family) FROM member_session "
                + "WHERE device_type = 'H5' AND member_id = ?", Integer.class, memberId);
        assertThat(families).as("一次失败登录不应又多开一个会话族").isEqualTo(1);
    }

    @Test
    @DisplayName("refresh 轮换一次有效；旧 refresh 再用触发复用检测并整族撤销，旧 access 立刻 401")
    void refreshRotationDetectsReuse() throws Exception {
        String phone = phone("rotation");
        JsonNode first = login(phone, sendCode(phone, "LOGIN"), "APP");

        MvcResult refreshed = mockMvc.perform(post("/member/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + first.path("refreshToken").asText() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(codeOf(refreshed)).isEqualTo(200);
        String newAccess = body(refreshed).path("accessToken").asText();
        assertThat(newAccess).isNotBlank();

        // 旧 access 应立即失效（jti 已随轮换作废）
        mockMvc.perform(get("/member/me").header("Authorization", "Bearer " + first.path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        // 新 access 可用，证明吊销不是"一刀切全拒"
        mockMvc.perform(get("/member/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk());

        MvcResult replay = mockMvc.perform(post("/member/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + first.path("refreshToken").asText() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(codeOf(replay)).as("已轮换掉的 refresh 再出现即视为令牌被复制").isNotEqualTo(200);

        mockMvc.perform(get("/member/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isUnauthorized());
        Integer revoked = jdbc.queryForObject("SELECT COUNT(*) FROM member_session WHERE revoked_reason = "
                + "'REFRESH_REUSE' AND sess_state = 'REVOKED'", Integer.class);
        assertThat(revoked).as("复用检测必须留下整族撤销的痕迹").isPositive();
    }

    @Test
    @DisplayName("同设备类型互斥、不同设备类型并存（顶号只顶同类型）")
    void sameDeviceTypeIsExclusiveAcrossTypesCoexist() throws Exception {
        String phone = phone("device-mutex");
        JsonNode h5First = login(phone, sendCode(phone, "LOGIN"), "H5");

        JsonNode h5Second = login(phone, sendCode(phone, "LOGIN"), "H5");
        MvcResult kicked = mockMvc.perform(post("/member/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + h5First.path("refreshToken").asText() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(codeOf(kicked)).as("同类型旧会话应被顶下线").isNotEqualTo(200);

        JsonNode vehicle = login(phone, sendCode(phone, "LOGIN"), "VEHICLE");
        assertThat(vehicle.path("sessionFamily").asText())
                .isNotEqualTo(h5Second.path("sessionFamily").asText());
        for (String refreshToken : new String[]{h5Second.path("refreshToken").asText(),
                vehicle.path("refreshToken").asText()}) {
            MvcResult ok = mockMvc.perform(post("/member/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(codeOf(ok)).as("H5 与车机属于不同设备类型，应并存").isEqualTo(200);
        }
        Integer activeFamilies = jdbc.queryForObject("SELECT COUNT(DISTINCT session_family) FROM member_session "
                + "WHERE sess_state = 'ACTIVE'", Integer.class);
        assertThat(activeFamilies).isPositive();
    }

    @Test
    @DisplayName("两域隔离：串域一律 401，不能出现 403（403 意味着已进入后台权限判定）")
    void crossRealmTokensAreRejectedWith401() throws Exception {
        String phone = phone("realm");
        JsonNode member = login(phone, sendCode(phone, "LOGIN"), "H5");
        String memberToken = member.path("accessToken").asText();

        mockMvc.perform(get("/barrier/strategies").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/member/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/barrier/strategies").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("实名提交后 realname_state 变 VERIFIED（MOCK 通道自动通过）")
    void realnameSubmissionUpdatesProjection() throws Exception {
        String phone = phone("realname");
        JsonNode tokens = login(phone, sendCode(phone, "LOGIN"), "H5");
        String access = tokens.path("accessToken").asText();
        String code = sendCode(phone, "REALNAME");

        mockMvc.perform(post("/member/me/realname")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"张三丰\",\"idNo\":\"11010119900307391X\",\"smsCode\":\""
                                + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("VERIFIED"));

        mockMvc.perform(get("/member/me").header("Authorization", "Bearer " + access))
                .andExpect(jsonPath("$.data.realnameState").value("VERIFIED"));

        Map<String, Object> row = jdbc.queryForMap("SELECT mask_name, mask_id_no, id_no_cipher, real_name_cipher "
                + "FROM member_realname WHERE member_id = ?", tokens.path("memberId").asLong());
        assertThat(String.valueOf(row.get("mask_name"))).startsWith("张");
        assertThat(String.valueOf(row.get("mask_id_no"))).contains("*");
        assertThat(String.valueOf(row.get("id_no_cipher"))).isNotEqualTo("11010119900307391X");
        assertThat(jdbc.queryForObject("SELECT LENGTH(idcard_hash) FROM member_user WHERE id = ?",
                Integer.class, tokens.path("memberId").asLong()))
                .as("实名真相在 member_realname，但 member_user 的投影列也必须同步写（否则台账与事实不一致）")
                .isEqualTo(64);
    }
}
