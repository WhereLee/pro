package com.lrs.buddy;

import com.lrs.buddy.biz.swap.provision.DeviceProvisionService;
import com.lrs.buddy.biz.swap.service.SwapLedgerService;
import com.lrs.buddy.framework.common.util.CryptoUtil;
import com.lrs.buddy.framework.iot.config.IotProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台只读接口的冒烟测试（柜机 / 电池 / 差异 / 订单详情）。
 *
 * 为什么要专门有这样一类"只看得到 200"的测试：这些接口全是**手写 SQL 的读模型**，
 * 列名写错（比如把 {@code c.device_row_id} 写成 {@code d.device_row_id}）在单测的业务断言里
 * 不一定暴露——只有真的执行那条 SQL 才会红。这不是推测：本批就是这样在手工冒烟时才发现的，
 * 于是把它固化成 CI 断言，而不是每次靠人肉起服务。
 */
class AdminSwapReadTest extends AbstractIntegrationTest {

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

    @Test
    @DisplayName("三个列表接口都能真的执行成功（SQL 列名与投影成立）")
    void readEndpointsExecute() throws Exception {
        long stamp = System.nanoTime();
        var credential = provision.register("SWAP-CAB-8", "CABO-RD-" + stamp, "只读冒烟柜");
        jdbc.update("UPDATE iot_device SET secret_cipher = ? WHERE id = ?",
                CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), "rd-secret-" + stamp),
                credential.deviceRowId());
        String cabinetNo = "CAB-RD-" + stamp;
        ledger.createCabinet(1L, "SWAP-CAB-8", cabinetNo, credential.deviceId(), 6, "SC-6", null);
        ledger.registerBattery(cabinetNo, 1, "BAT-RD-" + stamp, "BAT-60V20AH", 90,
                new BigDecimal("26.0"), new BigDecimal("20.0"), new BigDecimal("60.0"));

        mockMvc.perform(get("/swap/cabinets").header(HttpHeaders.AUTHORIZATION, authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[?(@.cabinet_no == '" + cabinetNo + "')]").exists());

        mockMvc.perform(get("/swap/batteries").header(HttpHeaders.AUTHORIZATION, authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/swap/discrepancies").header(HttpHeaders.AUTHORIZATION, authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/swap/cabinets/" + cabinetNo + "/slots").header(HttpHeaders.AUTHORIZATION, authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("订单分页与详情：分页结构成立，未知单号是业务错误而不是 500")
    void orderPageAndDetail() throws Exception {
        mockMvc.perform(get("/swap/orders").header(HttpHeaders.AUTHORIZATION, authHeader())
                        .param("pageNum", "1").param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").isNumber());

        mockMvc.perform(get("/swap/orders/SWAP-NOT-EXIST").header(HttpHeaders.AUTHORIZATION, authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    @DisplayName("待复核队列可读且返回数组结构")
    void pendingQueueIsReadable() throws Exception {
        // admin 持有全部权限码，这里只验接口存在与返回形状；
        // “无 approve 码就不能复核”由 @PreAuthorize 注解 + 权限码种子（V16）共同保证，
        // 而“注解里的码必须存在于种子”已由契约测试统一扫描。
        mockMvc.perform(get("/swap/orders/interventions/pending").header(HttpHeaders.AUTHORIZATION, authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }
}
