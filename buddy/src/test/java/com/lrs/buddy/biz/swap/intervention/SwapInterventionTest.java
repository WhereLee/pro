package com.lrs.buddy.biz.swap.intervention;

import com.lrs.buddy.biz.swap.order.OrderState;
import com.lrs.buddy.biz.swap.provision.DeviceProvisionService;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import com.lrs.buddy.biz.swap.service.SwapLedgerService;
import com.lrs.buddy.biz.swap.service.SwapOrderService;
import com.lrs.buddy.framework.common.util.CryptoUtil;
import com.lrs.buddy.framework.iot.config.IotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单人工干预的双人复核（M2 B3）。
 *
 * 这里真正要保的是**审计链在失败路径上也得留住**：第 6 个用例先申请、再把订单改成终态模拟并发，
 * 然后复核执行必然失败——断言的不是"报错了"，而是"申请单仍然是 FAILED + 有审批人 + 有失败原因"。
 * 如果审批记录和执行动作在同一个事务里，这条记录会被回滚掉，事后完全看不出有人试图干预过。
 */
@SpringBootTest
@ActiveProfiles("test")
class SwapInterventionTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String BATTERY_PRODUCT = "BAT-60V20AH";
    private static final AtomicLong SEQ = new AtomicLong();
    private static final long APPLICANT = 9001L;
    private static final long APPROVER = 9002L;

    @Autowired
    private SwapInterventionService interventions;
    @Autowired
    private SwapOrderService orders;
    @Autowired
    private SwapLedgerService ledger;
    @Autowired
    private DeviceProvisionService provision;
    @Autowired
    private SwapOrderRepository repo;
    @Autowired
    private IotProperties properties;
    @Autowired
    private JdbcTemplate jdbc;

    private String cabinetNo;
    private long member;

    @BeforeEach
    void setUp() {
        long stamp = System.nanoTime();
        cabinetNo = "CAB-IV-" + stamp;
        member = seedMember();
        var credential = provision.register(PRODUCT_KEY, "CABO-IV-" + stamp, "干预测试柜");
        jdbc.update("UPDATE iot_device SET secret_cipher = ?, online_state = 'ONLINE' WHERE id = ?",
                CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), "iv-secret-" + stamp),
                credential.deviceRowId());
        ledger.createCabinet(1L, PRODUCT_KEY, cabinetNo, credential.deviceId(), 8, null, null);
        ledger.registerBattery(cabinetNo, 1, "BAT-IV-" + stamp, BATTERY_PRODUCT, 95,
                new BigDecimal("26.0"), new BigDecimal("20.0"), new BigDecimal("60.0"));
    }

    @Test
    @DisplayName("两步人工干预：ABORT 只冻结现场进待核资，RESOLVE_ABORTED 才做补偿并落 ABORTED")
    void approveByAnotherPersonExecutesAndCompensates() {
        String orderNo = createdOrder();
        long abortId = interventions.apply(orderNo, "ADMIN_ABORT", "柜机故障无法继续，人工中止", APPLICANT, "运营甲");
        assertThat(state(abortId)).isEqualTo("PENDING");

        String afterAbort = interventions.approveAndExecute(abortId, APPROVER, "运营乙");

        // 关键语义：人工判中止不等于“没换成”，所以不得在这一格里退权益、释放预占
        assertThat(afterAbort).as("ADMIN_ABORT 的落点是待核资而不是 ABORTED")
                .isEqualTo(OrderState.FAILED_MANUAL.name());
        assertThat(state(abortId)).isEqualTo("EXECUTED");
        assertThat(repo.findByOrderNo(orderNo).rightState())
                .as("现场必须保持冻结：权益仍是预占").isEqualTo("OCCUPIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot_reservation WHERE order_id = ? "
                + "AND resv_state = 'ACTIVE'", Integer.class, orderIdOf(orderNo)))
                .as("待核资期间仓位仍被预占（否则可能被分给别人）").isEqualTo(2);

        long resolveId = interventions.apply(orderNo, "ADMIN_RESOLVE_ABORTED", "现场核实：电池未取走，可完全回滚",
                APPLICANT, "运营甲");
        String afterResolve = interventions.approveAndExecute(resolveId, APPROVER, "运营乙");

        assertThat(afterResolve).isEqualTo(OrderState.ABORTED.name());
        SwapInterventionService.InterventionView view = interventions.ofOrder(orderNo).stream()
                .filter(v -> v.id() == resolveId).findFirst().orElseThrow();
        assertThat(view.approverId()).isEqualTo(APPROVER);
        assertThat(view.approvedAt()).isNotNull();
        assertThat(view.executedAt()).as("执行时刻必须可查，否则“什么时候真的动了”无人能答").isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot_reservation WHERE order_id = ? "
                + "AND resv_state = 'ACTIVE'", Integer.class, orderIdOf(orderNo))).isZero();
        assertThat(jdbc.queryForObject("SELECT times_occupied FROM swap_right_account WHERE member_id = ?",
                Integer.class, member)).isZero();
    }

    @Test
    @DisplayName("申请人不能自批（代码先拦，DB CHECK 是第二道）")
    void applicantCannotApproveOwnRequest() {
        String orderNo = createdOrder();
        long id = interventions.apply(orderNo, "ADMIN_ABORT", "需要人工中止处理", APPLICANT, "运营甲");

        assertThatThrownBy(() -> interventions.approveAndExecute(id, APPLICANT, "运营甲"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("另一个人");

        assertThat(state(id)).as("自批被拒后申请仍待处理，不能被偷偷置成已批准").isEqualTo("PENDING");

        // 绕过代码直接写库也必须失败：这条防线不能只活在 Java 里
        assertThatThrownBy(() -> jdbc.update("UPDATE swap_intervention SET approver_id = ?, apply_state = 'APPROVED' "
                + "WHERE id = ?", APPLICANT, id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("同一笔单不允许两条待处理申请（否则可能被两人各批各执行）")
    void onlyOnePendingRequestPerOrder() {
        String orderNo = createdOrder();
        interventions.apply(orderNo, "ADMIN_ABORT", "柜机故障无法继续，人工中止", APPLICANT, "运营甲");

        assertThatThrownBy(() -> interventions.apply(orderNo, "ADMIN_ABORT", "重复申请应被拒绝",
                APPROVER, "运营乙"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已有待处理");
    }

    @Test
    @DisplayName("申请阶段就校验状态机：终态单不允许再申请中止")
    void illegalActionIsRejectedAtApplyTime() {
        String orderNo = createdOrder();
        // active_user 是生成列（终态自动为 NULL），不得写它：写了会被 H2/MySQL 直接拒绝
        jdbc.update("UPDATE swap_order SET order_state = 'COMPLETED', update_time = CURRENT_TIMESTAMP "
                + "WHERE order_no = ?", orderNo);

        assertThatThrownBy(() -> interventions.apply(orderNo, "ADMIN_ABORT", "对已完成单申请中止",
                APPLICANT, "运营甲"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不允许执行");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_intervention WHERE order_no = ?",
                Integer.class, orderNo)).as("注定失败的申请不该进复核队列占位").isZero();
    }

    @Test
    @DisplayName("驳回：申请单变 REJECTED，订单状态不变")
    void rejectKeepsOrderUntouched() {
        String orderNo = createdOrder();
        long id = interventions.apply(orderNo, "ADMIN_ABORT", "希望中止这一单", APPLICANT, "运营甲");

        interventions.reject(id, APPROVER, "运营乙", "证据不足，先让超时驱动收敛");

        assertThat(state(id)).isEqualTo("REJECTED");
        assertThat(interventions.ofOrder(orderNo).get(0).rejectReason()).contains("证据不足");
        assertThat(jdbc.queryForObject("SELECT order_state FROM swap_order WHERE order_no = ?", String.class, orderNo))
                .as("驳回不能对订单做任何事").isEqualTo(OrderState.AUTHORIZED.name());
    }

    @Test
    @DisplayName("执行失败也必须留下审批记录与失败原因（审计链不依赖执行成功）")
    void failedExecutionStillKeepsApprovalTrail() {
        String orderNo = createdOrder();
        long id = interventions.apply(orderNo, "ADMIN_ABORT", "准备人工中止", APPLICANT, "运营甲");
        // 申请后订单被别人推进到终态（模拟并发）：此刻执行必然非法
        jdbc.update("UPDATE swap_order SET order_state = 'COMPLETED', update_time = CURRENT_TIMESTAMP "
                + "WHERE order_no = ?", orderNo);

        assertThatThrownBy(() -> interventions.approveAndExecute(id, APPROVER, "运营乙"))
                .isInstanceOf(RuntimeException.class);

        SwapInterventionService.InterventionView view = interventions.ofOrder(orderNo).get(0);
        assertThat(view.applyState()).isEqualTo("FAILED");
        assertThat(view.approverId()).as("谁批准的必须留下，不能随异常一起回滚").isEqualTo(APPROVER);
        assertThat(view.approvedAt()).isNotNull();
        assertThat(view.execError()).isNotBlank();
    }

    @Test
    @DisplayName("人工判定完成：资金实扣 + 释放预占 + 记差异台账，但不伪造设备事实改归属")
    void resolveCompletedDeductsButDoesNotFakeOwnership() {
        String orderNo = createdOrder();
        long orderId = orderIdOf(orderNo);
        jdbc.update("UPDATE swap_order SET order_state = 'FAILED_MANUAL', update_time = CURRENT_TIMESTAMP "
                + "WHERE order_no = ?", orderNo);

        long id = interventions.apply(orderNo, "ADMIN_RESOLVE_COMPLETED", "现场已核实电池确实被取走",
                APPLICANT, "运营甲");
        String after = interventions.approveAndExecute(id, APPROVER, "运营乙");

        assertThat(after).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT right_state FROM swap_order WHERE id = ?", String.class, orderId))
                .isEqualTo("DEDUCTED");
        assertThat(jdbc.queryForObject("SELECT times_used FROM swap_right_account WHERE member_id = ?",
                Integer.class, member)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_discrepancy WHERE order_id = ? AND kind = "
                + "'FACT_MISSING'", Integer.class, orderId))
                .as("人工落终必须留下一条差异台账，说明这笔没有设备事实支撑").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_battery_binding WHERE order_id = ?",
                Integer.class, orderId))
                .as("归属变更不能由人工判定伪造，需另走归属核销").isZero();
    }

    @Test
    @DisplayName("待复核队列只含 PENDING，分页列表带展示态")
    void pendingQueueAndPageView() {
        String orderNo = createdOrder();
        interventions.apply(orderNo, "ADMIN_ABORT", "柜机离线过久，需人工处理", APPLICANT, "运营甲");

        List<SwapInterventionService.InterventionView> pending = interventions.byStatus("PENDING", 50);
        assertThat(pending).isNotEmpty();
        assertThat(pending).allMatch(v -> "PENDING".equals(v.applyState()));
        assertThat(repo.pageOrders(OrderState.AUTHORIZED.name(), orderNo, 0, 20)).hasSize(1);
    }

    // ---------------- 夹具 ----------------

    private String createdOrder() {
        return orders.create(member, cabinetNo, "H5", "iv-" + System.nanoTime()).orderNo();
    }

    private long orderIdOf(String orderNo) {
        return repo.findByOrderNo(orderNo).id();
    }

    private String state(long id) {
        return jdbc.queryForObject("SELECT apply_state FROM swap_intervention WHERE id = ?", String.class, id);
    }

    private long seedMember() {
        long id = 990_000L + SEQ.incrementAndGet();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO member_user (id, member_no, nickname, realname_state, member_state, register_source, "
                        + "create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'VERIFIED', 'NORMAL', 'H5', ?,?, 0, 0, 1)",
                id, "M" + id, "干预测试会员", now, now);
        jdbc.update("INSERT INTO swap_right_account (id, member_id, plan_id, times_total, times_used, times_occupied, "
                        + "valid_from, valid_until, freeze_state, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?, ?, 1, 60, 0, 0, ?, '2099-12-31 00:00:00', 'NORMAL', ?, ?, 0, 0, 1)",
                id * 10, id, now, now, now);
        return id;
    }
}
