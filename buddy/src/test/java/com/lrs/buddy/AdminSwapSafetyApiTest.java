package com.lrs.buddy;

import com.lrs.buddy.biz.swap.provision.DeviceProvisionService;
import com.lrs.buddy.biz.swap.service.SwapLedgerService;
import com.lrs.buddy.framework.common.util.CryptoUtil;
import com.lrs.buddy.framework.iot.config.IotProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全联动与补偿/对账的受控接口（M3 阶段 1）。
 *
 * 覆盖三件事，每件都对应一个"没有这层就只能靠人肉起服务才发现"的问题：
 * <ol>
 *   <li>接口存在且能真的跑通（{@code compensations} 是手写 SQL 的读模型，列名写错在这里才红）；</li>
 *   <li>没带令牌是 401 而不是 200 —— 安全动作不能出现"忘了鉴权也能触发"；</li>
 *   <li>参数校验在 Security 之后、业务之前生效：缺依据的联动请求是业务错误码，不是 500。</li>
 * </ol>
 *
 * <p>"注解里的权限码必须存在于 Flyway 种子"这条由 {@code SwapDdlContractTest} 统一扫描，
 * 本类不重复建一套角色数据。
 */
class AdminSwapSafetyApiTest extends AbstractIntegrationTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private SwapLedgerService ledger;
    @Autowired
    private DeviceProvisionService provision;
    @Autowired
    private IotProperties properties;
    @Autowired
    private JdbcTemplate jdbc;

    private String authHeader() {
        return "Bearer " + adminToken;
    }

    /** 专属站点：站点级联动会扫站内全部柜机，用共享的 site=1 会把别的用例的柜机一起锁掉。 */
    private long seedSiteWithCabinet() {
        long stamp = System.nanoTime();
        long siteId = 910_000L + SEQ.incrementAndGet();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO swap_site (id, site_no, site_name, product_key, enabled, create_time, "
                        + "update_time, version, del_flag, tenant_id) VALUES (?,?,?,?, 1, ?, ?, 0, 0, 1)",
                siteId, "SITE-API-" + stamp, "受控接口站点", PRODUCT_KEY, now, now);
        var credential = provision.register(PRODUCT_KEY, "CABO-API-" + stamp, "受控接口柜");
        jdbc.update("UPDATE iot_device SET secret_cipher = ?, online_state = 'ONLINE' WHERE id = ?",
                CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), "api-secret-" + stamp),
                credential.deviceRowId());
        String cabinetNo = "CAB-API-" + stamp;
        ledger.createCabinet(siteId, PRODUCT_KEY, cabinetNo, credential.deviceId(), 8, null, null);
        return siteId;
    }

    @Test
    @DisplayName("紧急停充受控接口：带依据触发成功，返回逐步计数")
    void emergencyStopEndpointRunsLinkage() throws Exception {
        long siteId = seedSiteWithCabinet();

        mockMvc.perform(post("/swap/safety/emergency-stop")
                        .header(HttpHeaders.AUTHORIZATION, authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "siteId", siteId, "reason", "接口冒烟：温度阶跃告警", "alarmRef", "ALARM-API-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.cabinets").value(1))
                .andExpect(jsonPath("$.data.stopCommandsFailed").value(0))
                .andExpect(jsonPath("$.data.ordersAbortFailed").value(0));
    }

    @Test
    @DisplayName("紧急停充缺依据：业务错误码，不是 500，也不是静默执行")
    void emergencyStopRejectsMissingReason() throws Exception {
        long siteId = seedSiteWithCabinet();

        mockMvc.perform(post("/swap/safety/emergency-stop")
                        .header(HttpHeaders.AUTHORIZATION, authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("siteId", siteId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));

        mockMvc.perform(post("/swap/safety/emergency-stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "siteId", siteId, "reason", "没带令牌也必须被挡在安全链上"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("补偿台账可读、对账与补偿可手动触发一次")
    void compensationReadAndManualTriggersWork() throws Exception {
        seedSiteWithCabinet();

        mockMvc.perform(get("/swap/compensations").header(HttpHeaders.AUTHORIZATION, authHeader())
                        .param("compState", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(post("/swap/safety/reconcile").header(HttpHeaders.AUTHORIZATION, authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNumber());

        mockMvc.perform(post("/swap/safety/compensation/sweep").header(HttpHeaders.AUTHORIZATION, authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNumber());
    }
}
