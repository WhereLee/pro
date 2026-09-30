package com.lrs.buddy.biz.swap;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrs.buddy.biz.member.service.MemberAuthService;
import com.lrs.buddy.biz.swap.display.DisplayState;
import com.lrs.buddy.biz.swap.order.OrderState;
import com.lrs.buddy.biz.swap.provision.DeviceProvisionService;
import com.lrs.buddy.biz.swap.service.SwapLedgerService;
import com.lrs.buddy.framework.common.util.CryptoUtil;
import com.lrs.buddy.framework.iot.config.IotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C 端换电视图与主链路接口（B2）。
 *
 * 这个测试类真正要保的不是"接口能返回 200"，而是三条展示语义：
 * 1 **未知/挂起/人工核资一律不得渲染成失败**（tone 不能是 danger，且不许重复下单）；
 * 2 只有 REJECTED / ABORTED 才是 danger；
 * 3 归属校验在服务端做（别人的单查不到也动不了）。
 *
 * `start`（开归还仓）在 test profile 下没有 broker，所以断言的是"设备不可达时给出业务错误
 * 而不是假装成功"——真 MQTT 的完整闭环由 SwapFlowTest 负责，这里不重复也不假装。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberSwapViewTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String BATTERY_PRODUCT = "BAT-60V20AH";
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Autowired
    private MemberAuthService auth;
    @Autowired
    private SwapLedgerService ledger;
    @Autowired
    private DeviceProvisionService provision;
    @Autowired
    private IotProperties properties;
    @Autowired
    private JdbcTemplate jdbc;

    private String cabinetNo;

    @BeforeEach
    void setUp() {
        long stamp = System.nanoTime();
        cabinetNo = "CAB-H5-" + stamp;
        var credential = provision.register(PRODUCT_KEY, "CABO-H5-" + stamp, "H5 测试柜");
        jdbc.update("UPDATE iot_device SET secret_cipher = ?, online_state = 'ONLINE' WHERE id = ?",
                CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), "h5-secret-" + stamp),
                credential.deviceRowId());
        ledger.createCabinet(1L, PRODUCT_KEY, cabinetNo, credential.deviceId(), 8, null, null);
        ledger.registerBattery(cabinetNo, 1, "BAT-H5-" + stamp, BATTERY_PRODUCT, 96,
                new BigDecimal("25.0"), new BigDecimal("20.0"), new BigDecimal("60.0"));
        ledger.registerBattery(cabinetNo, 2, "BAT-H5-B-" + stamp, BATTERY_PRODUCT, 94,
                new BigDecimal("25.0"), new BigDecimal("20.0"), new BigDecimal("60.0"));
    }

    /** 走完注册即登录 + 实名（MOCK 自动通过），拿到可用 access 令牌。 */
    private String verifiedMemberToken() {
        String phone = "138" + String.format("%08d", SEQ.incrementAndGet() % 100_000_000);
        var login = auth.login(phone, auth.sendCode(phone, "LOGIN").echoCode(), "H5", "fp-" + phone, "127.0.0.1");
        auth.submitRealname(login.memberId(), "李测试", "1101011990" + String.format("%07d",
                        SEQ.incrementAndGet() % 10_000_000) + "X",
                auth.sendCode(phone, "REALNAME").echoCode());
        // 新会员额度为 0 是正确的领域行为（未购买套餐不得换电，购买属 M4），
        // 所以这里显式授予额度；不授额度的情形由 unverifiedMemberIsRejectedOnRealname 反面覆盖。
        grantRights(login.memberId());
        return login.accessToken();
    }

    private void grantRights(long memberId) {
        jdbc.update("UPDATE swap_right_account SET times_total = 60, valid_from = ?, valid_until = '2099-12-31 "
                + "00:00:00' WHERE member_id = ?", Timestamp.valueOf(LocalDateTime.now()), memberId);
    }

    @Test
    @DisplayName("展示语义：未知/挂起/人工核资绝不是失败样式，且不许重复下单")
    void unknownAndSuspendedAreNeverRenderedAsFailure() {
        // 全订单态必须有映射（漏登记直接抛，不让它悄悄落到前端的默认分支）
        for (OrderState state : OrderState.values()) {
            assertThat(DisplayState.of(state)).as("订单态 %s 必须登记 C 端展示映射", state).isNotNull();
        }
        assertThat(DisplayState.of(OrderState.UNCONFIRMED).tone()).isEqualTo("warn");
        assertThat(DisplayState.of(OrderState.SUSPENDED).tone()).isEqualTo("warn");
        assertThat(DisplayState.of(OrderState.FAILED_MANUAL).tone()).isEqualTo("warn");
        assertThat(DisplayState.of(OrderState.UNCONFIRMED).canReorder())
                .as("未知态允许重复下单会产生两笔并发现场").isFalse();
        assertThat(DisplayState.of(OrderState.SUSPENDED).canReorder()).isFalse();
        // 允许 danger 的只有明确负向终态
        assertThat(DisplayState.of(OrderState.REJECTED).tone()).isEqualTo("danger");
        assertThat(DisplayState.of(OrderState.ABORTED).tone()).isEqualTo("danger");
        assertThat(DisplayState.of(OrderState.COMPLETED).tone()).isEqualTo("success");
    }

    @Test
    @DisplayName("找柜 → 建单 → 进度：六步齐全，可执行动作由服务端给出")
    void findCabinetThenCreateAndTrack() throws Exception {
        String token = verifiedMemberToken();

        JsonNode cabinets = dataOf(mockMvc.perform(get("/member/swap/cabinets")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());
        assertThat(cabinets.toString()).as("柜机列表必须能查到刚建的柜").contains(cabinetNo);

        JsonNode created = dataOf(mockMvc.perform(post("/member/swap/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cabinetNo\":\"" + cabinetNo + "\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(created.path("accepted").asBoolean()).as("拒绝原因：%s", created.path("rejectReasons")).isTrue();
        assertThat(created.path("displayState").asText()).isEqualTo("WAIT_OPEN");
        String orderNo = created.path("orderNo").asText();

        JsonNode progress = dataOf(mockMvc.perform(get("/member/swap/orders/" + orderNo)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());
        assertThat(progress.path("steps")).hasSize(6);
        assertThat(progress.path("canStart").asBoolean()).isTrue();
        assertThat(progress.path("canDeclareClosed").asBoolean()).isFalse();
        assertThat(progress.path("returnSlotNo").isNull()).isFalse();
        assertThat(progress.path("offerSlotNo").isNull()).isFalse();
        assertThat(progress.path("hint").asText()).isNotBlank();

        // 设备侧在 test profile 下不可达：必须是业务错误，不能假装开仓成功
        MvcResult start = mockMvc.perform(post("/member/swap/orders/" + orderNo + "/start")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        assertThat(codeOf(start)).as("设备不可达时不得把订单推进 RETURNING").isNotEqualTo(200);
        assertThat(stateOf(orderNo)).isEqualTo("AUTHORIZED");
    }

    @Test
    @DisplayName("别人的单：查不到也动不了（归属校验在服务端）")
    void otherMembersOrderIsRejected() throws Exception {
        String owner = verifiedMemberToken();
        String orderNo = dataOf(mockMvc.perform(post("/member/swap/orders")
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cabinetNo\":\"" + cabinetNo + "\"}")).andReturn()).path("orderNo").asText();

        String intruder = verifiedMemberToken();
        MvcResult read = mockMvc.perform(get("/member/swap/orders/" + orderNo)
                .header("Authorization", "Bearer " + intruder)).andExpect(status().isOk()).andReturn();
        assertThat(codeOf(read)).as("会员 A 的单不能被会员 B 读到").isNotEqualTo(200);

        MvcResult act = mockMvc.perform(post("/member/swap/orders/" + orderNo + "/declare-closed")
                .header("Authorization", "Bearer " + intruder)).andExpect(status().isOk()).andReturn();
        assertThat(codeOf(act)).isNotEqualTo(200);
    }

    @Test
    @DisplayName("未知态在进度接口上返回 UNKNOWN + warn + 不许重下单")
    void unconfirmedOrderShowsUnknown() throws Exception {
        String token = verifiedMemberToken();
        String orderNo = dataOf(mockMvc.perform(post("/member/swap/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cabinetNo\":\"" + cabinetNo + "\"}")).andReturn()).path("orderNo").asText();
        long orderId = jdbc.queryForObject("SELECT id FROM swap_order WHERE order_no = ?", Long.class, orderNo);
        // 直接摆到未知态：展示层不判断原因，只如实呈现，所以这里不需要走完整超时流程
        jdbc.update("UPDATE swap_order SET order_state = 'UNCONFIRMED', update_time = CURRENT_TIMESTAMP WHERE id = ?",
                orderId);

        JsonNode progress = dataOf(mockMvc.perform(get("/member/swap/orders/" + orderNo)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());
        assertThat(progress.path("displayState").asText()).isEqualTo("UNKNOWN");
        assertThat(progress.path("tone").asText()).isEqualTo("warn");
        assertThat(progress.path("canReorder").asBoolean()).isFalse();
        assertThat(progress.path("label").asText()).doesNotContain("失败");
    }

    @Test
    @DisplayName("未实名会员建单被拒，且拒绝原因就是实名")
    void unverifiedMemberIsRejectedOnRealname() throws Exception {
        String phone = "137" + String.format("%08d", SEQ.incrementAndGet() % 100_000_000);
        var login = auth.login(phone, auth.sendCode(phone, "LOGIN").echoCode(), "H5", "fp-x", "127.0.0.1");

        JsonNode created = dataOf(mockMvc.perform(post("/member/swap/orders")
                .header("Authorization", "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cabinetNo\":\"" + cabinetNo + "\"}")).andReturn());
        assertThat(created.path("accepted").asBoolean()).isFalse();
        assertThat(created.path("rejectReasons").toString()).contains("REALNAME_NONE");
        assertThat(created.path("displayState").asText()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("无令牌访问 C 端换电接口一律 401")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/member/swap/cabinets")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/member/swap/orders/current")).andExpect(status().isUnauthorized());
    }

    // ---------------- 工具 ----------------

    private JsonNode dataOf(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(json.path("code").asInt())
                .as("期望成功，实际响应：" + result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return json.path("data");
    }

    private int codeOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").asInt();
    }

    private String stateOf(String orderNo) {
        return jdbc.queryForObject("SELECT order_state FROM swap_order WHERE order_no = ?", String.class, orderNo);
    }
}
