package com.lrs.buddy.biz.swap.compensation;

import com.lrs.buddy.biz.swap.provision.DeviceProvisionService;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import com.lrs.buddy.biz.swap.service.SwapLedgerService;
import com.lrs.buddy.biz.swap.service.SwapOrderService;
import com.lrs.buddy.framework.common.util.CryptoUtil;
import com.lrs.buddy.framework.iot.config.IotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 补偿执行器（M3 阶段 1）。
 *
 * 断言集中在补偿的四条纪律上：可重试（幂等）、失败要退避且留原因、重试耗尽要转人工、
 * **没有执行者的动作绝不假装完成**。最后一条是本测试最想守住的：
 * 把没人执行的动作标成 DONE/SKIPPED，账面就永远看不出这笔资产没人管过。
 */
@SpringBootTest
@ActiveProfiles("test")
class SwapCompensationExecutorTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String BATTERY_PRODUCT = "BAT-60V20AH";
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private SwapCompensationExecutor executor;
    @Autowired
    private SwapOrderRepository repo;
    @Autowired
    private SwapOrderService orders;
    @Autowired
    private SwapLedgerService ledger;
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
        cabinetNo = "CAB-CX-" + stamp;
        member = seedMember();
        var credential = provision.register(PRODUCT_KEY, "CABO-CX-" + stamp, "补偿执行器柜");
        jdbc.update("UPDATE iot_device SET secret_cipher = ?, online_state = 'ONLINE' WHERE id = ?",
                CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), "cx-secret-" + stamp),
                credential.deviceRowId());
        ledger.createCabinet(1L, PRODUCT_KEY, cabinetNo, credential.deviceId(), 8, null, null);
        ledger.registerBattery(cabinetNo, 1, "BAT-CX-" + stamp, BATTERY_PRODUCT, 95,
                new BigDecimal("26.0"), new BigDecimal("20.0"), new BigDecimal("60.0"));
    }

    @Test
    @DisplayName("释放预占：执行一次即 DONE，重复执行不叠加效果")
    void releaseReservationIsIdempotent() {
        long orderId = createdOrder();
        insertCompensation(orderId, "RELEASE_RESERVATION", "SLOT", null);

        executor.runOnce();

        assertThat(stateOf(orderId, "RELEASE_RESERVATION")).isEqualTo("DONE");
        assertThat(activeReservations(orderId)).isZero();
        // 再跑一次不应有任何变化（此刻已无 PENDING 行，也不会重新打开）
        executor.runOnce();
        assertThat(releasedCount(orderId)).isEqualTo(2);
    }

    @Test
    @DisplayName("解除绑定：重试第二次不会把已经归档的绑定再动一次")
    void unbindBindingIsIdempotent() {
        long orderId = createdOrder();
        long battery = anyBattery();
        bind(battery, member, orderId);
        insertCompensation(orderId, "UNBIND_USER_BATTERY", "BATTERY", battery);

        executor.runOnce();
        assertThat(activeBindings(battery)).isZero();

        // 模拟"业务已生效但台账没写成功"的重试场景：把台账退回 PENDING 再跑
        jdbc.update("UPDATE swap_compensation SET comp_state = 'PENDING', done_at = NULL WHERE order_id = ? "
                + "AND action = 'UNBIND_USER_BATTERY'", orderId);
        executor.runOnce();
        assertThat(stateOf(orderId, "UNBIND_USER_BATTERY")).isEqualTo("DONE");
        assertThat(activeBindings(battery)).isZero();
        assertThat(historicalBindings(battery)).as("只应归档一条，不能重复关").isEqualTo(1);
    }

    @Test
    @DisplayName("本域没有执行者的动作：保持待处理并记差异，绝不假装完成")
    void unknownExecutorActionStaysPending() {
        long orderId = createdOrder();
        insertCompensation(orderId, "CREATE_WORK_ORDER", "ORDER", null);

        executor.runOnce();

        String state = stateOf(orderId, "CREATE_WORK_ORDER");
        assertThat(state).as("M5 域动作不能被标成 DONE/SKIPPED").isNotIn("DONE", "SKIPPED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_compensation WHERE order_id = ? AND attempts = 1",
                Integer.class, orderId)).as("必须累加尝试次数并留痕").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_discrepancy WHERE kind = 'FACT_MISSING' "
                + "AND remark LIKE '%无执行者%'", Integer.class)).isPositive();
    }

    @Test
    @DisplayName("执行失败：写退避时间与原因，不静默")
    void failedExecutionRecordsBackoffAndReason() {
        long orderId = createdOrder();
        // 权益置成"无可退预占"，让 RELEASE_RIGHT 必然失败
        jdbc.update("UPDATE swap_order SET right_state = 'OCCUPIED', update_time = CURRENT_TIMESTAMP WHERE id = ?",
                orderId);
        jdbc.update("UPDATE swap_right_account SET times_occupied = 0 WHERE member_id = ?", member);
        insertCompensation(orderId, "RELEASE_RIGHT", "RIGHT", null);

        executor.runOnce();

        var row = jdbc.queryForMap("SELECT comp_state, attempts, last_error, next_retry_at FROM swap_compensation "
                + "WHERE order_id = ? AND action = 'RELEASE_RIGHT'", orderId);
        assertThat(row.get("comp_state")).isEqualTo("FAILED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(row.get("last_error"))).contains("释放失败");
        assertThat(row.get("next_retry_at")).as("必须安排下一次重试，不能丢").isNotNull();
    }

    @Test
    @DisplayName("重试耗尽：记一条差异并转人工（不永远悄悄重试）")
    void exhaustedRetriesEscalate() {
        long orderId = createdOrder();
        jdbc.update("UPDATE swap_order SET right_state = 'OCCUPIED', update_time = CURRENT_TIMESTAMP WHERE id = ?",
                orderId);
        jdbc.update("UPDATE swap_right_account SET times_occupied = 0 WHERE member_id = ?", member);
        insertCompensation(orderId, "RELEASE_RIGHT", "RIGHT", null);
        jdbc.update("UPDATE swap_compensation SET attempts = 7, next_retry_at = NULL WHERE order_id = ? "
                + "AND action = 'RELEASE_RIGHT'", orderId);

        executor.runOnce();

        assertThat(jdbc.queryForObject("SELECT attempts FROM swap_compensation WHERE order_id = ? "
                + "AND action = 'RELEASE_RIGHT'", Integer.class, orderId)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_discrepancy WHERE kind = 'FACT_MISSING' "
                + "AND remark LIKE '%重试耗尽%'", Integer.class)).isPositive();
    }

    @Test
    @DisplayName("WRITE_DISCREPANCY：必须真的写出一条差异；remark 没写 kind 就失败，不能被标成已完成")
    void writeDiscrepancyActuallyWrites() {
        long orderId = createdOrder();
        // 这里的重点不是“能写”，而是“写不出时不能谎报完成”：以前这个动作是个 noop，
        // 执行器把它直接标 DONE——账面说差异已记，一行都没有，而这正是事后最查不出来的那种洞。
        insertCompensationWithRemark(orderId, "WRITE_DISCREPANCY", "ORDER", null, "只写了说明没写差异类型");
        executor.runOnce();

        assertThat(stateOf(orderId, "WRITE_DISCREPANCY")).as("无 kind 不能算完成").isNotIn("DONE", "SKIPPED");
        assertThat(discrepanciesOfOrder(orderId)).isZero();

        resetForRetry(orderId, "WRITE_DISCREPANCY", "ASSET_LEDGER:柜说仓里有电池，云端账上没有");
        executor.runOnce();

        assertThat(stateOf(orderId, "WRITE_DISCREPANCY")).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_discrepancy WHERE order_id = ? "
                + "AND kind = 'ASSET_LEDGER' AND dedup_key LIKE 'COMP:%'", Integer.class, orderId)).isEqualTo(1);

        // 幂等：重试同一条时去重键已存在，只能还是一条，不能多出一行
        resetForRetry(orderId, "WRITE_DISCREPANCY", "ASSET_LEDGER:柜说仓里有电池，云端账上没有");
        executor.runOnce();
        assertThat(discrepanciesOfOrder(orderId)).as("重试不得叠加差异条数").isEqualTo(1);
    }

    @ParameterizedTest(name = "第 {0} 次失败后等 {1} 秒，上限 1800 秒")
    @CsvSource({"0,30", "1,60", "2,120", "10,1800", "30,1800"})
    @DisplayName("退避是指数增长且有上限")
    void backoffGrowsThenSaturates(int attempts, int expectedSeconds) {
        assertThat(SwapCompensationExecutor.backoff(attempts)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    // ---------------- 夹具 ----------------

    private long createdOrder() {
        SwapOrderService.CreateResult created = orders.create(member, cabinetNo, "H5", "cx-" + System.nanoTime());
        if (!created.accepted()) {
            throw new IllegalStateException("建单被拒：" + created.rejectReasons());
        }
        return created.orderId();
    }

    private void insertCompensation(long orderId, String action, String targetType, Long targetId) {
        insertCompensationWithRemark(orderId, action, targetType, targetId, null);
    }

    private void insertCompensationWithRemark(long orderId, String action, String targetType, Long targetId,
                                              String remark) {
        jdbc.update("INSERT INTO swap_compensation (id, order_id, action, target_type, target_id, comp_state, "
                        + "attempts, remark, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?,?,?, 'PENDING', 0, ?, ?, ?, 0, 0, 1)",
                nextId(), orderId, action, targetType, targetId, remark, Timestamp.valueOf(LocalDateTime.now()),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    private void resetForRetry(long orderId, String action, String remark) {
        jdbc.update("UPDATE swap_compensation SET comp_state = 'PENDING', done_at = NULL, next_retry_at = NULL, "
                + "remark = ? WHERE order_id = ? AND action = ?", remark, orderId, action);
    }

    private int discrepanciesOfOrder(long orderId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM swap_discrepancy WHERE order_id = ?", Integer.class,
                orderId);
        return n == null ? 0 : n;
    }

    private String stateOf(long orderId, String action) {
        return jdbc.queryForObject("SELECT comp_state FROM swap_compensation WHERE order_id = ? AND action = ? "
                + "ORDER BY id DESC LIMIT 1", String.class, orderId, action);
    }

    private int activeReservations(long orderId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot_reservation WHERE order_id = ? "
                + "AND resv_state = 'ACTIVE'", Integer.class, orderId);
        return n == null ? 0 : n;
    }

    private int releasedCount(long orderId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot_reservation WHERE order_id = ? "
                + "AND resv_state = 'RELEASED'", Integer.class, orderId);
        return n == null ? 0 : n;
    }

    private long anyBattery() {
        return jdbc.queryForObject("SELECT id FROM swap_battery WHERE del_flag = 0 ORDER BY id DESC LIMIT 1",
                Long.class);
    }

    private void bind(long batteryId, long userId, long orderId) {
        jdbc.update("INSERT INTO swap_battery_binding (id, battery_id, user_id, bind_state, bind_source, order_id, "
                        + "evidence, start_at, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'ACTIVE', 'ORDER', ?, 'STRONG', ?,?, ?, 0, 0, 1)",
                nextId(), batteryId, userId, orderId, Timestamp.valueOf(LocalDateTime.now()),
                Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));
    }

    private int activeBindings(long batteryId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM swap_battery_binding WHERE battery_id = ? "
                + "AND bind_state = 'ACTIVE'", Integer.class, batteryId);
        return n == null ? 0 : n;
    }

    private int historicalBindings(long batteryId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM swap_battery_binding WHERE battery_id = ? "
                + "AND bind_state = 'HIST' AND end_reason = 'COMP_UNBIND'", Integer.class, batteryId);
        return n == null ? 0 : n;
    }

    private long nextId() {
        return 700_000_000L + SEQ.incrementAndGet();
    }

    private long seedMember() {
        // 会员 id 段按测试类分块（全量套件共用一个 H2）：跟 SwapFlowTest 同用 950_000 时，
        // 单跑不报错、全量跑先插入者赢——本批第一次 clean verify 就是这样红的。
        long id = 985_000L + SEQ.incrementAndGet();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO member_user (id, member_no, nickname, realname_state, member_state, register_source, "
                        + "create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'VERIFIED', 'NORMAL', 'H5', ?,?, 0, 0, 1)",
                id, "M" + id, "补偿测试会员", now, now);
        jdbc.update("INSERT INTO swap_right_account (id, member_id, plan_id, times_total, times_used, times_occupied, "
                        + "valid_from, valid_until, freeze_state, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?, ?, 1, 60, 0, 1, ?, '2099-12-31 00:00:00', 'NORMAL', ?, ?, 0, 0, 1)",
                id * 10, id, now, now, now);
        return id;
    }
}
