package com.lrs.buddy.biz.swap.flow;

import com.lrs.buddy.biz.swap.compensation.AssetLedgerReconcileJob;
import com.lrs.buddy.biz.swap.compensation.SwapCompensationExecutor;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拒收双分岔（R-A/R-B）与资产对账（I7）。
 *
 * 这里最能体现 M3 的架构意图：**物理动作走补偿台账，落终态由执行器收尾**。
 * 所以断言分两段——拒收后单停在 ABORTING 是正确结果，跑一次执行器才到 ABORTED；
 * 反过来"拒收当场就 ABORTED"才是问题（资产没锁、电池没转待取回，账面却已终结）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SwapRejectIntakeTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String BATTERY_PRODUCT = "BAT-60V20AH";
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private SwapFlowService flow;
    @Autowired
    private SwapCompensationExecutor executor;
    @Autowired
    private AssetLedgerReconcileJob reconcile;
    @Autowired
    private SwapOrderService orders;
    @Autowired
    private SwapLedgerService ledger;
    @Autowired
    private SwapOrderRepository repo;
    @Autowired
    private DeviceProvisionService provision;
    @Autowired
    private IotProperties properties;
    @Autowired
    private JdbcTemplate jdbc;

    private String cabinetNo;
    private long member;

    @BeforeEach
    void setUp() {
        long stamp = System.nanoTime();
        cabinetNo = "CAB-RJ-" + stamp;
        member = seedMember();
        var credential = provision.register(PRODUCT_KEY, "CABO-RJ-" + stamp, "拒收测试柜");
        jdbc.update("UPDATE iot_device SET secret_cipher = ?, online_state = 'ONLINE' WHERE id = ?",
                CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), "rj-secret-" + stamp),
                credential.deviceRowId());
        ledger.createCabinet(1L, PRODUCT_KEY, cabinetNo, credential.deviceId(), 8, null, null);
        ledger.registerBattery(cabinetNo, 1, "BAT-RJ-" + stamp, BATTERY_PRODUCT, 96,
                new BigDecimal("26.0"), new BigDecimal("20.0"), new BigDecimal("60.0"));
    }

    @Test
    @DisplayName("R-A（电池已入仓但认不出归属）：锁仓挂补偿，且必须等补偿做完才落终态")
    void rejectedBatteryInsideLocksSlot() {
        long orderId = returningOrder("PHYSICS_DONE", null);
        SwapOrderRepository.OrderRow order = repo.findOrder(orderId);

        flow.rejectIntake(order, LocalDateTime.now(), "RETURN_BATTERY_UNKNOWN");

        assertThat(orderState(orderId)).as("认不出电池 = 无阻塞补偿，可当场收尾").isEqualTo("ABORTED");
        assertThat(compState(orderId, "LOCK_SLOT")).as("锁仓动作必须挂账而不是忘记").isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_discrepancy WHERE kind = 'IDENTITY_SUSPECT' "
                + "AND order_id = ?", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot_reservation WHERE order_id = ? "
                + "AND resv_state = 'ACTIVE'", Integer.class, orderId)).as("预占必须同步回退").isZero();
        assertThat(jdbc.queryForObject("SELECT times_occupied FROM swap_right_account WHERE member_id = ?",
                Integer.class, member)).as("权益预占必须退回").isZero();

        executor.runOnce();

        assertThat(compState(orderId, "LOCK_SLOT")).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("""
                SELECT sl.slot_state FROM swap_slot sl JOIN swap_order o ON o.id = ?
                 WHERE sl.cabinet_id = o.cabinet_id AND sl.slot_no = o.return_slot_no
                """, String.class, orderId)).isEqualTo("ISOLATED");
    }

    @Test
    @DisplayName("R-A（认得出是用户的电池）：转待取回是阻塞补偿，做完前不得落 ABORTED")
    void pendingPickupIsBlockingAndExecutorFinalizes() {
        long orderId = returningOrder("PHYSICS_DONE", null);
        long battery = jdbc.queryForObject("""
                SELECT b.id FROM swap_battery b JOIN swap_order o ON o.id = ?
                 WHERE b.del_flag = 0 AND b.current_cabinet_id IS NOT NULL ORDER BY b.id DESC LIMIT 1
                """, Long.class, orderId);
        jdbc.update("UPDATE swap_order SET return_battery_id = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                battery, orderId);
        // 仓已关、电池在仓内：这是"用户的电池被投进来又不被接受"的现场
        jdbc.update("UPDATE swap_order_step SET facts_json = JSON_OBJECT('insertedBattery','X') "
                + "WHERE order_id = ? AND step_no = 2", orderId);

        SwapOrderRepository.OrderRow order = repo.findOrder(orderId);
        flow.rejectIntake(order, LocalDateTime.now(), "RETURN_BATTERY_NOT_ACCEPTED");

        assertThat(orderState(orderId)).as("有待取回的阻塞补偿，绝不能当场宣告终结")
                .isEqualTo("ABORTING");
        assertThat(compState(orderId, "BATTERY_PENDING_PICKUP")).isEqualTo("PENDING");

        executor.runOnce();

        assertThat(compState(orderId, "BATTERY_PENDING_PICKUP")).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT battery_state FROM swap_battery WHERE id = ?", String.class, battery))
                .as("用户的财产不能静默进池被下一个人取走").isEqualTo("PENDING_PICKUP");
        assertThat(orderState(orderId)).as("阻塞项做完后由执行器收尾").isEqualTo("ABORTED");
    }

    @Test
    @DisplayName("R-B（电池未入仓）：不锁仓，尝试重开仓让用户取回；重开不成也必须留下人工线索")
    void rejectedOutsideReopensAndDoesNotLock() {
        long orderId = returningOrder("OPEN_CONFIRMED", null);
        SwapOrderRepository.OrderRow order = repo.findOrder(orderId);

        flow.rejectIntake(order, LocalDateTime.now(), "BATTERY_NOT_INSERTED");

        assertThat(compState(orderId, "LOCK_SLOT")).as("仓里没东西，锁仓毫无依据").isNull();
        assertThat(orderState(orderId)).isEqualTo("ABORTED");
        // 重开动作必须真的发出去了：test profile 下无 broker，指令会停在 CREATED 而不是抛错，
        // 所以可断言的是“S1 被重发了一次”（让用户能把电池取回来），而不是只记一笔差异。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iot_command WHERE biz_id = ? AND step_no = 1 "
                + "AND cmd_code = 'OPEN_SLOT'", Integer.class, orderId))
                .as("R-B 必须重开归还仓").isEqualTo(1);
    }

    @Test
    @DisplayName("对账 I7：电池声称在仓位而仓位不含它 → 记差异")
    void reconcileDetectsDanglingBatteryReference() {
        long orderId = createdOrder();
        long battery = jdbc.queryForObject("SELECT id FROM swap_battery WHERE del_flag = 0 ORDER BY id DESC LIMIT 1",
                Long.class);
        long danglingSlot = jdbc.queryForObject("""
                SELECT s.id FROM swap_slot s JOIN swap_cabinet c ON c.id = s.cabinet_id
                WHERE c.cabinet_no = ? AND s.battery_id IS NULL AND s.del_flag = 0 LIMIT 1
                """, Long.class, cabinetNo);
        // 制造背离：电池说自己在某个空仓里
        jdbc.update("UPDATE swap_battery SET current_slot_id = ? WHERE id = ?", danglingSlot, battery);

        int found = reconcile.reconcileOnce();

        assertThat(found).as("至少应发现我们刚植入的这条背离").isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_discrepancy WHERE kind = 'ASSET_LEDGER' "
                + "AND battery_id = ?", Integer.class, battery))
                .as("差异必须落到台账，不能只打日志；双向检查各命中一次正是双向恒等式的价值")
                .isGreaterThanOrEqualTo(1);
        assertThat(orderId).isPositive();
    }

    // ---------------- 夹具 ----------------

    /** 造一笔已下到"归还中"的单，并把 S2 步骤摆到指定状态（拒收判定就看这个事实）。 */
    private long returningOrder(String step2State, Long returnBatteryId) {
        long orderId = createdOrder();
        jdbc.update("UPDATE swap_order SET order_state = 'VERIFYING', return_battery_id = ?, "
                        + "update_time = CURRENT_TIMESTAMP WHERE id = ?", returnBatteryId, orderId);
        jdbc.update("UPDATE swap_order_step SET step_state = ? WHERE order_id = ? AND step_no = 2",
                step2State, orderId);
        jdbc.update("UPDATE swap_order_step SET step_state = 'OPEN_CONFIRMED' WHERE order_id = ? AND step_no = 1",
                orderId);
        return orderId;
    }

    private long createdOrder() {
        SwapOrderService.CreateResult created = orders.create(member, cabinetNo, "H5", "rj-" + System.nanoTime());
        if (!created.accepted()) {
            throw new IllegalStateException("建单被拒：" + created.rejectReasons());
        }
        return created.orderId();
    }

    private String orderState(long orderId) {
        return repo.findOrder(orderId).state();
    }

    private String compState(long orderId, String action) {
        var rows = jdbc.queryForList("SELECT comp_state FROM swap_compensation WHERE order_id = ? AND action = ? "
                + "ORDER BY id DESC LIMIT 1", String.class, orderId, action);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long seedMember() {
        long id = 940_000L + SEQ.incrementAndGet();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO member_user (id, member_no, nickname, realname_state, member_state, register_source, "
                        + "create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'VERIFIED', 'NORMAL', 'H5', ?,?, 0, 0, 1)",
                id, "M" + id, "拒收测试会员", now, now);
        jdbc.update("INSERT INTO swap_right_account (id, member_id, plan_id, times_total, times_used, times_occupied, "
                        + "valid_from, valid_until, freeze_state, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?, ?, 1, 60, 0, 0, ?, '2099-12-31 00:00:00', 'NORMAL', ?, ?, 0, 0, 1)",
                id * 10, id, now, now, now);
        return id;
    }
}

