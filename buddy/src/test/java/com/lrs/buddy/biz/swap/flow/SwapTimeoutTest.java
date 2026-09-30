package com.lrs.buddy.biz.swap.flow;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 超时与反查驱动（§5.3 / §5.4 / §5.6）。
 *
 * 用例都用 SQL 把订单/步骤/仓位摆到"已经过期"的现场，再调一次驱动 ——
 * 而不是 sleep 等真实时钟：等 150 秒的测试既跑不动也测不到分支，
 * 而且真实项目里超时分支恰恰是最少被覆盖、出事最多的一类。
 */
@SpringBootTest
@ActiveProfiles("test")
class SwapTimeoutTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String BATTERY_PRODUCT = "BAT-60V20AH";
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private SwapTimeoutDriver driver;
    @Autowired
    private SwapFlowService flow;
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
        cabinetNo = "CAB-TO-" + stamp;
        member = seedMember();
        var credential = provision.register(PRODUCT_KEY, "CABO-TO-" + stamp, "超时测试柜");
        jdbc.update("UPDATE iot_device SET secret_cipher = ?, online_state = 'ONLINE' WHERE id = ?",
                CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), "to-secret-" + stamp),
                credential.deviceRowId());
        ledger.createCabinet(1L, PRODUCT_KEY, cabinetNo, credential.deviceId(), 8, null, null);
        ledger.registerBattery(cabinetNo, 1, "BAT-TO-" + stamp, BATTERY_PRODUCT, 95,
                new BigDecimal("26.0"), new BigDecimal("20.0"), new BigDecimal("60.0"));
    }

    @Test
    @DisplayName("S1 超时 + 反查确认门没开：重发一次而不是判失败")
    void s1TimeoutWithClosedDoorRedispatchesOnce() {
        long orderId = orderIn(OrderState.RETURNING);
        markStep(orderId, 1, "DISPATCHED", null);
        seedCommand(orderId, 1, "OPEN_SLOT");
        // 反查需要**新鲜**的投影：没测过的仓不是"门没开"，而是"不知道开没开"（不能拿它当重发依据）
        SwapOrderRepository.OrderRow order = repo.findOrder(orderId);
        freshDoor(order.cabinetId(), order.returnSlotNo(), "CLOSED");

        driver.sweepOnce();

        assertThat(stateOf(orderId)).isEqualTo("RETURNING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iot_command WHERE biz_id = ? AND cmd_code = 'OPEN_SLOT'",
                Integer.class, orderId)).as("反查说没开 → 允许重发一次").isEqualTo(2);
        assertThat(eventTypes(orderId)).contains("deadline_S1_door_closed");
    }

    @Test
    @DisplayName("S1 超时 + 投影说门已开：按事实继续，不重发（FI-01 的收敛路径）")
    void s1TimeoutWithOpenedDoorContinuesByFact() {
        long orderId = orderIn(OrderState.RETURNING);
        markStep(orderId, 1, "DISPATCHED", null);
        seedCommand(orderId, 1, "OPEN_SLOT");
        SwapOrderRepository.OrderRow order = repo.findOrder(orderId);
        freshDoor(order.cabinetId(), order.returnSlotNo(), "OPEN");

        driver.sweepOnce();

        assertThat(stepState(orderId, 1)).isEqualTo("OPEN_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iot_command WHERE biz_id = ? AND cmd_code = 'OPEN_SLOT'",
                Integer.class, orderId)).as("门已开却重发 = 重复副作用，正是要防的事").isEqualTo(1);
    }

    @Test
    @DisplayName("S1 超时且无从断定：既不判成功也不判失败，落 UNCONFIRMED")
    void s1TimeoutUnknowableGoesUnconfirmed() {
        long orderId = orderIn(OrderState.RETURNING);
        markStep(orderId, 1, "DISPATCHED", null);
        // 一条在途 + 一条已被重发时置为 SUPERSEDED：同一步骤只允许一条在途指令（uk_icmd_active），
        // 摆两条 DISPATCHED 本身就是非法现场
        seedCommand(orderId, 1, "OPEN_SLOT", "SUPERSEDED");
        seedCommand(orderId, 1, "OPEN_SLOT", "DISPATCHED");
        SwapOrderRepository.OrderRow order = repo.findOrder(orderId);
        // 投影是陈旧的（last_detected_at 过期）→ 不能当结论用
        staleDoor(order.cabinetId(), order.returnSlotNo(), "CLOSED");

        driver.sweepOnce();

        assertThat(stateOf(orderId)).isEqualTo("UNCONFIRMED");
        assertThat(stepState(orderId, 1)).isEqualTo("CONFIRM_PENDING");
        assertThat(repo.findOrder(orderId).rightState())
                .as("不可断定期间禁止任何自动资金动作").isEqualTo("OCCUPIED");
    }

    @Test
    @DisplayName("门已开但用户没投电池：超时挂起并锁住该仓，不误判成失败")
    void noInsertTimeoutSuspendsOrder() {
        long orderId = orderIn(OrderState.RETURNING);
        markStep(orderId, 1, "OPEN_CONFIRMED", null);

        driver.sweepOnce();

        assertThat(stateOf(orderId)).isEqualTo("SUSPENDED");
        assertThat(eventTypes(orderId)).contains("deadline_no_insert");
    }

    @Test
    @DisplayName("挂起到期无人处理：电池转待取回、补偿做完才落 ABORTED")
    void suspendedTimeoutCompensatesThenAborts() {
        String oldCode = oldBatteryCode();
        long orderId = orderIn(OrderState.SUSPENDED);
        Long battery = jdbc.queryForObject("SELECT id FROM swap_battery WHERE battery_code = ?", Long.class, oldCode);
        jdbc.update("UPDATE swap_order SET return_battery_id = ? WHERE id = ?", battery, orderId);
        markStep(orderId, 2, "OPEN_CONFIRMED", "{\"insertedBattery\":\"" + oldCode + "\"}");

        driver.sweepOnce();

        assertThat(stateOf(orderId)).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("SELECT battery_state FROM swap_battery WHERE id = ?", String.class, battery))
                .as("用户的旧电池被平台暂存，必须进 PENDING_PICKUP，不能静默回池").isEqualTo("PENDING_PICKUP");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot_reservation WHERE order_id = ? "
                + "AND resv_state = 'ACTIVE'", Integer.class, orderId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot WHERE reserved_order_id = ? "
                + "AND slot_state = 'RESERVED_ORDER'", Integer.class, orderId))
                .as("I7：预占释放后仓态必须同步回落，否则那个仓会被永久认为已占用").isZero();
        assertThat(jdbc.queryForObject("SELECT times_occupied FROM swap_right_account WHERE member_id = ?",
                Integer.class, member)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_right_transaction WHERE order_id = ? AND kind "
                + "IN ('OCCUPY','RELEASE')", Integer.class, orderId))
                .as("预占与释放都要留流水，否则资金侧不可审计").isEqualTo(2);
        assertThat(eventTypes(orderId)).containsSequence("deadline_suspended", "abort_done");
    }

    @Test
    @DisplayName("不可断定超时：落人工终态，绝不自动退权益")
    void unconfirmedTimeoutGoesToManual() {
        long orderId = orderIn(OrderState.UNCONFIRMED);

        driver.sweepOnce();

        assertThat(stateOf(orderId)).isEqualTo("FAILED_MANUAL");
        assertThat(repo.findOrder(orderId).rightState())
                .as("冻结预占，等人工核资后决定扣/退，不能自动退").isEqualTo("OCCUPIED");
    }

    @Test
    @DisplayName("AUTHORIZED 超时且设备不可达：补下发失败时保持原状，不硬推状态")
    void authorizedTimeoutWithoutDeviceStalls() {
        long orderId = orderIn(OrderState.AUTHORIZED);
        markStep(orderId, 1, "PENDING", null);

        driver.sweepOnce();

        assertThat(stateOf(orderId)).as("设备不可达时宁可挂着，也不把订单推到 RETURNING").isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iot_command WHERE biz_id = ?", Integer.class, orderId))
                .as("失败的补下发不该留下永远发不出的指令行").isZero();
    }

    @Test
    @DisplayName("用户声明已关门（B2）：反查通过才继续，第二次只能转人工并锁仓")
    void declareClosedRequiresVerifiedProbe() {
        String oldCode = oldBatteryCode();
        long orderId = orderIn(OrderState.RETURNING);
        markStep(orderId, 1, "OPEN_CONFIRMED", null);
        markStep(orderId, 2, "OPEN_CONFIRMED", "{\"insertedBattery\":\"" + oldCode + "\"}");
        SwapOrderRepository.OrderRow order = repo.findOrder(orderId);
        freshDoor(order.cabinetId(), order.returnSlotNo(), "CLOSED");
        jdbc.update("UPDATE swap_order SET order_state = 'SUSPENDED', update_time = CURRENT_TIMESTAMP WHERE id = ?",
                orderId);

        String first = flow.declareClosed(orderId, member);

        assertThat(first).as("反查说门关了且电池在 → 可自助恢复一次").isEqualTo("RESUMED");
        assertThat(stateOf(orderId)).isEqualTo("OFFERING");
        assertThat(jdbc.queryForObject("SELECT self_resume_used FROM swap_order WHERE id = ?", Integer.class, orderId))
                .as("自助恢复只能一次，这个标记必须被消费掉").isEqualTo(1);
        assertThatThrownBy(() -> flow.declareClosed(orderId, member))
                .as("已不在挂起态的第二次声明必须被拒，而不是又推一次")
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------------- 夹具 ----------------

    /** 建一单后直接摆到指定状态：驱动只看现场事实，摆现场是它的正确测法。 */
    private long orderIn(OrderState state) {
        SwapOrderService.CreateResult created = orders.create(member, cabinetNo, "H5", "to-" + System.nanoTime());
        jdbc.update("UPDATE swap_order SET order_state = ?, deadline_ts = ?, deadline_at = ?, update_time = ? "
                        + "WHERE id = ?", state.name(), System.currentTimeMillis() - 1000,
                Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()), created.orderId());
        return created.orderId();
    }

    private void markStep(long orderId, int stepNo, String state, String factsJson) {
        jdbc.update("UPDATE swap_order_step SET step_state = ?, facts_json = ?, deadline_ts = ?, update_time = ? "
                + "WHERE order_id = ? AND step_no = ?", state, factsJson, System.currentTimeMillis() - 1000,
                Timestamp.valueOf(LocalDateTime.now()), orderId, stepNo);
    }

    private String oldBatteryCode() {
        String code = "BAT-OLD-" + System.nanoTime();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO swap_battery (id, battery_code, product_key, battery_state, own_type, holder_user_id, "
                        + "location_state, soc, soh, cycle_count, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'HELD_BY_USER', 'USER_OWNED', ?, 'KNOWN', 30, 95, 20, ?,?, 0, 0, 1)",
                7_000_000L + SEQ.incrementAndGet(), code, BATTERY_PRODUCT, member, now, now);
        return code;
    }

    private void seedCommand(long orderId, int stepNo, String cmdCode) {
        seedCommand(orderId, stepNo, cmdCode, "DISPATCHED");
    }

    private void seedCommand(long orderId, int stepNo, String cmdCode, String cmdState) {
        boolean inFlight = "DISPATCHED".equals(cmdState) || "CREATED".equals(cmdState)
                || "ACKED".equals(cmdState) || "UNCONFIRMED".equals(cmdState);
        jdbc.update("INSERT INTO iot_command (id, cmd_id, biz_type, biz_id, step_no, device_row_id, product_key, "
                        + "topic, cmd_code, qos, priority, cmd_state, retry_left, retry_max, ttl_sec, sent_ts, "
                        + "deadline_ts, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?, 'SWAP_ORDER', ?, ?, ?, ?, '/x', ?, 1, 100, ?, 0, 0, 15, ?, ?, ?, ?, 0, 0, 1)",
                8_000_000L + SEQ.incrementAndGet(), "CMD" + SEQ.incrementAndGet() + "X", orderId, stepNo,
                deviceRow(), PRODUCT_KEY, cmdCode, cmdState, System.currentTimeMillis(),
                inFlight ? null : System.currentTimeMillis() - 1000,
                Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));
    }

    private Long deviceRow() {
        return jdbc.queryForObject("SELECT device_row_id FROM swap_cabinet WHERE cabinet_no = ?", Long.class, cabinetNo);
    }

    private void freshDoor(long cabinetId, Integer slotNo, String state) {
        jdbc.update("UPDATE swap_slot SET door_state = ?, last_detected_at = ? WHERE cabinet_id = ? AND slot_no = ?",
                state, Timestamp.valueOf(LocalDateTime.now()), cabinetId, slotNo);
    }

    private void staleDoor(long cabinetId, Integer slotNo, String state) {
        jdbc.update("UPDATE swap_slot SET door_state = ?, last_detected_at = ? WHERE cabinet_id = ? AND slot_no = ?",
                state, Timestamp.valueOf(LocalDateTime.now().minusDays(1)), cabinetId, slotNo);
    }

    private String stateOf(long orderId) {
        return repo.findOrder(orderId).state();
    }

    private String stepState(long orderId, int stepNo) {
        Map<String, Object> step = repo.step(orderId, stepNo);
        return step == null ? null : String.valueOf(step.get("step_state"));
    }

    private List<String> eventTypes(long orderId) {
        return jdbc.queryForList("SELECT event_type FROM swap_order_event WHERE order_id = ? ORDER BY seq_no",
                String.class, orderId);
    }

    private long seedMember() {
        long id = 980_000L + SEQ.incrementAndGet();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO member_user (id, member_no, nickname, realname_state, member_state, register_source, "
                        + "create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'VERIFIED', 'NORMAL', 'H5', ?,?, 0, 0, 1)",
                id, "M" + id, "超时测试会员", now, now);
        jdbc.update("INSERT INTO swap_right_account (id, member_id, plan_id, times_total, times_used, times_occupied, "
                        + "valid_from, valid_until, freeze_state, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?, ?, 1, 60, 0, 0, ?, '2099-12-31 00:00:00', 'NORMAL', ?, ?, 0, 0, 1)",
                id * 10, id, now, now, now);
        return id;
    }
}
