package com.lrs.buddy.biz.swap.invariant;

import com.lrs.buddy.biz.swap.intervention.SwapInterventionService;
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
import org.springframework.dao.DuplicateKeyException;
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
 * 不变式 I1–I10 逐条实证（M2 收尾）。
 *
 * 与前面所有测试的区别：业务测试问"这条流程走不走得通"，这里问"**约束是不是真的兜得住**"。
 * 每条都尽量用"故意违反一次、期望被拒"的写法——正向流程测不出约束失效，
 * 例如 I9 的 per-battery 唯一：V8 建了生成列却漏建唯一索引，正向测试全绿也发现不了
 * （本批自查时发现，V17 补上，这条测试才第一次有意义）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SwapInvariantTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String BATTERY_PRODUCT = "BAT-60V20AH";
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private com.lrs.buddy.biz.swap.flow.SwapFlowService flow;
    @Autowired
    private SwapOrderService orders;
    @Autowired
    private SwapOrderRepository repo;
    @Autowired
    private SwapInterventionService interventions;
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
        cabinetNo = "CAB-INV-" + stamp;
        member = seedMember();
        var credential = provision.register(PRODUCT_KEY, "CABO-INV-" + stamp, "不变式测试柜");
        jdbc.update("UPDATE iot_device SET secret_cipher = ?, online_state = 'ONLINE' WHERE id = ?",
                CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), "inv-secret-" + stamp),
                credential.deviceRowId());
        ledger.createCabinet(1L, PRODUCT_KEY, cabinetNo, credential.deviceId(), 8, null, null);
        ledger.registerBattery(cabinetNo, 1, "BAT-INV-" + stamp, BATTERY_PRODUCT, 95,
                new BigDecimal("26.0"), new BigDecimal("20.0"), new BigDecimal("60.0"));
    }

    @Test
    @DisplayName("I1/I9：一块电池同时只能有一条生效绑定（V17 补的唯一索引真的兜得住）")
    void batteryHasAtMostOneActiveBinding() {
        long battery = anyBattery();
        long otherUser = seedMember();
        bind(battery, member, "ORDER");

        assertThatThrownBy(() -> bind(battery, otherUser, "ORDER"))
                .as("同一块电池分给两个人必须被 DB 拒绝")
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_battery_binding WHERE battery_id = ? "
                + "AND bind_state = 'ACTIVE'", Integer.class, battery)).isEqualTo(1);
    }

    @Test
    @DisplayName("I2：一个仓位同时只能被一笔在途订单预占")
    void slotHasAtMostOneActiveReservation() {
        long orderId = createdOrder().id();
        Long slotId = repo.slotRowId(cabinetId(), 2);
        assertThat(repo.reserveSlot(nextId(), slotId, orderId, "RETURN", LocalDateTime.now(), 1L)).isTrue();

        long otherOrder = createdOtherOrder();
        assertThat(repo.reserveSlot(nextId(), slotId, otherOrder, "RETURN", LocalDateTime.now(), 1L))
                .as("active_slot 生成列唯一索引必须拒掉第二笔预占").isFalse();
    }

    @Test
    @DisplayName("I3：扣减与所有权变更同事务——实扣失败时「人工判定完成」整体回滚")
    void deductionFailureRollsBackOwnershipDecision() {
        String orderNo = createdOrder().orderNo();
        long orderId = repo.findByOrderNo(orderNo).id();
        jdbc.update("UPDATE swap_order SET order_state = 'FAILED_MANUAL', right_state = 'OCCUPIED', "
                + "update_time = CURRENT_TIMESTAMP WHERE id = ?", orderId);
        long id = interventions.apply(orderNo, "ADMIN_RESOLVE_COMPLETED", "现场已核实电池被取走", 9001L, "甲");
        // 把账户的预占清零：实扣必然失败（CAS 影响行数 0）
        jdbc.update("UPDATE swap_right_account SET times_occupied = 0 WHERE member_id = ?", member);

        assertThatThrownBy(() -> interventions.approveAndExecute(id, 9002L, "乙"))
                .isInstanceOf(RuntimeException.class);

        assertThat(repo.findByOrderNo(orderNo).state())
                .as("扣不动就不许落 COMPLETED：否则这是一笔「没收到钱但已完成」的单")
                .isEqualTo("FAILED_MANUAL");
        assertThat(repo.findByOrderNo(orderNo).rightState()).isEqualTo("OCCUPIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_discrepancy WHERE order_id = ?",
                Integer.class, orderId)).as("回滚后不留差异台账").isZero();
        assertThat(jdbc.queryForObject("SELECT apply_state FROM swap_intervention WHERE id = ?",
                String.class, id)).as("审批记录必须留下（FAILED），失败也要可追责").isEqualTo("FAILED");
    }

    @Test
    @DisplayName("I4：在途订单必须都有生效 deadline（不存在「卡住且不会超时」的单）")
    void everyInflightOrderHasDeadline() {
        createdOrder();
        Integer missing = jdbc.queryForObject("SELECT COUNT(*) FROM swap_order WHERE active_user IS NOT NULL "
                + "AND (deadline_ts IS NULL OR deadline_at IS NULL)", Integer.class);
        assertThat(missing).as("生成列 active_user 标在途，非终态没有 deadline 就等于永远不会被超时驱动收敛")
                .isZero();
        Integer terminalWithDeadlineFieldNull = jdbc.queryForObject("SELECT COUNT(*) FROM swap_order "
                + "WHERE order_state IN ('COMPLETED','ABORTED','REJECTED') AND active_user IS NOT NULL", Integer.class);
        assertThat(terminalWithDeadlineFieldNull).as("终态必须让 active_user 归 NULL，否则挡住下一单").isZero();
    }

    @Test
    @DisplayName("I5：订单事件流可重放出当前状态（迁移链连续、末态等于投影）")
    void eventStreamReplaysToCurrentState() {
        String orderNo = createdOrder().orderNo();
        long id = interventions.apply(orderNo, "ADMIN_ABORT", "柜机故障，人工判中止", 9001L, "甲");
        interventions.approveAndExecute(id, 9002L, "乙");
        long second = interventions.apply(orderNo, "ADMIN_RESOLVE_ABORTED", "核实电池未取走，回滚", 9001L, "甲");
        String finalState = interventions.approveAndExecute(second, 9002L, "乙");

        List<Map<String, Object>> transitions = jdbc.queryForList("""
                SELECT seq_no, from_state, to_state, event_type FROM swap_order_event
                WHERE order_id = ? AND from_state IS NOT NULL AND to_state IS NOT NULL ORDER BY seq_no
                """, repo.findByOrderNo(orderNo).id());
        assertThat(transitions).as("至少要留下一条以上可重放的迁移").isNotEmpty();

        String replayed = null;
        for (Map<String, Object> row : transitions) {
            if (replayed != null) {
                assertThat(row.get("from_state"))
                        .as("事件流断裂：上一跳落在 %s，这一跳却从 %s 开始（重放会丢事实）",
                                replayed, row.get("from_state"))
                        .isEqualTo(replayed);
            }
            replayed = String.valueOf(row.get("to_state"));
        }
        assertThat(replayed).isEqualTo(finalState);
        assertThat(repo.findByOrderNo(orderNo).state()).isEqualTo(replayed);
    }

    @Test
    @DisplayName("I6：重复投递同一事件不产生第二次迁移（去重表唯一键 + 返回 false）")
    void duplicateEventIsNotAppliedTwice() {
        long orderId = createdOrder().id();
        String msgId = "dup-" + System.nanoTime();
        assertThat(repo.insertEventDedup(nextId(), orderId, "door_open", msgId, 3, LocalDateTime.now(), 1L)).isTrue();
        assertThat(repo.insertEventDedup(nextId(), orderId, "door_open", msgId, 3, LocalDateTime.now(), 1L))
                .as("同一 (order, event, msgId) 第二次必须被判重").isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_event_dedup WHERE order_id = ? AND msg_id = ?",
                Integer.class, orderId, msgId)).isEqualTo(1);
    }

    @Test
    @DisplayName("I7：资产台账恒等——由订单产生的绑定不能与电池归属矛盾")
    void assetLedgerIdentityHolds() {
        Integer doubleHeld = jdbc.queryForObject("SELECT COUNT(*) FROM swap_battery WHERE del_flag = 0 "
                + "AND current_slot_id IS NOT NULL AND holder_user_id IS NOT NULL", Integer.class);
        assertThat(doubleHeld).as("两个归属字段同时有值就是账实不一致").isZero();

        // 只统计“由订单产生”的绑定（order_id 非空）：本类的夹具绑定是 synthetic，
        // 把它计入等于用测试数据去证明产品约束，结论不成立。
        Integer boundButNotHeld = jdbc.queryForObject("""
                SELECT COUNT(*) FROM swap_battery_binding bd JOIN swap_battery b ON b.id = bd.battery_id
                WHERE bd.bind_state = 'ACTIVE' AND bd.order_id IS NOT NULL AND b.battery_state <> 'HELD_BY_USER'
                """, Integer.class);
        assertThat(boundButNotHeld).as("订单产生的生效绑定，电池状态必须是用户持有").isZero();
    }

    @Test
    @DisplayName("I8：补偿集未完成时禁止进入 ABORTED；完成后才放行")
    void abortedRequiresCompensationComplete() {
        // 正向：挂起单超时，补偿逐项落台账后才能落 ABORTED
        long clean = createdOrder().id();
        moveTo(clean, "SUSPENDED");
        flow.suspendTimeout(clean);
        assertThat(repo.findOrder(clean).state()).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_compensation WHERE order_id = ? AND comp_state "
                + "= 'DONE'", Integer.class, clean))
                .as("预占释放与权益退回都要有补偿台账行，否则 I8 无法审计").isPositive();
        assertThat(repo.countOpenCompensation(clean)).isZero();

        // 反向：人为留一条 PENDING 补偿项（模拟 M3 异步补偿排队中），必须拦住 ABORTED
        long blocked = createdOtherOrder();
        moveTo(blocked, "SUSPENDED");
        jdbc.update("INSERT INTO swap_compensation (id, order_id, action, target_type, comp_state, attempts, "
                        + "create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?, ?, 'UNBIND_USER_BATTERY', 'BATTERY', 'PENDING', 0, ?, ?, 0, 0, 1)",
                nextId(), blocked, Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));

        assertThatThrownBy(() -> flow.suspendTimeout(blocked))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("补偿未完成");

        // 不是停在 ABORTING，而是**整体回到 SUSPENDED**：守卫抛异常会把同一事务里的
        // 迁移与已做补偿一起回滚。这比“状态已推进但只做了一半补偿”对得多：
        // 下一轮超时驱动会重试，不会出现“预占已释放但订单还在 ABORTING”这种中间态。
        assertThat(repo.findOrder(blocked).state()).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_compensation WHERE order_id = ? AND comp_state = 'DONE'",
                Integer.class, blocked))
                .as("回滚后不得留下“已 DONE 但没提交”的补偿行").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot_reservation WHERE order_id = ? AND resv_state = 'ACTIVE'",
                Integer.class, blocked))
                .as("补偿与迁移同事务：既然没落终态，预占就必须仍在（仓仍属于这一单）")
                .isEqualTo(2);

        // 把未完成项做完后重试：先走超时入口推进到 ABORTING，再由“补偿收尾”入口落终态
        jdbc.update("UPDATE swap_compensation SET comp_state = 'DONE', done_at = ? WHERE order_id = ? "
                + "AND comp_state = 'PENDING'", Timestamp.valueOf(LocalDateTime.now()), blocked);
        flow.suspendTimeout(blocked);
        assertThat(repo.findOrder(blocked).state()).isEqualTo("ABORTED");
    }

    private void moveTo(long orderId, String state) {
        jdbc.update("UPDATE swap_order SET order_state = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?", state, orderId);
    }

    @Test
    @DisplayName("I8 异步收尾入口：只有 ABORTING 的单能补偿收尾，非 ABORTING 一律拒")
    void compensationFinisherIsNarrowlyScoped() {
        long orderId = createdOrder().id();
        assertThatThrownBy(() -> flow.finishAborting(orderId))
                .as("从 AUTHORIZED 直接“收尾”必须被拒：否则 ABORTED 可以绕过补偿集条件出现")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ABORTING");

        moveTo(orderId, "ABORTING");
        flow.finishAborting(orderId);
        assertThat(repo.findOrder(orderId).state()).isEqualTo("ABORTED");
    }

    @Test
    @DisplayName("I10：绑定来源枚举里没有观测通道，观测数据无法改归属")
    void observationCannotCreateBinding() {
        long battery = anyBattery();
        assertThatThrownBy(() -> bind(battery, member, "OBSERVATION"))
                .as("bind_source 是闭集枚举，观测通道根本不是一个合法来源")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_battery WHERE id = ? AND holder_user_id = ?",
                Integer.class, battery, member)).isZero();
    }

    // ---------------- 夹具 ----------------

    private SwapOrderRepository.OrderRow createdOrder() {
        SwapOrderService.CreateResult created = orders.create(member, cabinetNo, "H5", "inv-" + System.nanoTime());
        if (!created.accepted()) {
            throw new IllegalStateException("建单被拒：" + created.rejectReasons());
        }
        return repo.findOrder(created.orderId());
    }

    private long createdOtherOrder() {
        long other = seedMember();
        return orders.create(other, cabinetNo, "H5", "inv2-" + System.nanoTime()).orderId();
    }

    private long cabinetId() {
        return repo.findCabinet(cabinetNo).id();
    }

    private long anyBattery() {
        return jdbc.queryForObject("SELECT id FROM swap_battery WHERE del_flag = 0 ORDER BY id DESC LIMIT 1",
                Long.class);
    }

    private void bind(long batteryId, long userId, String source) {
        jdbc.update("INSERT INTO swap_battery_binding (id, battery_id, user_id, bind_state, bind_source, order_id, "
                        + "evidence, start_at, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'ACTIVE', ?, NULL, 'STRONG', ?,?, ?, 0, 0, 1)",
                nextId(), batteryId, userId, source, Timestamp.valueOf(LocalDateTime.now()),
                Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));
        // 夹具自己也要遵守 I7：绑同步写持有态，不然 I7 会被自己的测试数据判红
        jdbc.update("UPDATE swap_battery SET battery_state = 'HELD_BY_USER', holder_user_id = ?, current_slot_id = "
                + "NULL, current_cabinet_id = NULL, update_time = CURRENT_TIMESTAMP WHERE id = ?", userId, batteryId);
    }

    private long nextId() {
        return 600_000_000L + SEQ.incrementAndGet();
    }

    private long seedMember() {
        long id = 960_000L + SEQ.incrementAndGet();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO member_user (id, member_no, nickname, realname_state, member_state, register_source, "
                        + "create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'VERIFIED', 'NORMAL', 'H5', ?,?, 0, 0, 1)",
                id, "M" + id, "不变式会员", now, now);
        jdbc.update("INSERT INTO swap_right_account (id, member_id, plan_id, times_total, times_used, times_occupied, "
                        + "valid_from, valid_until, freeze_state, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?, ?, 1, 60, 0, 0, ?, '2099-12-31 00:00:00', 'NORMAL', ?, ?, 0, 0, 1)",
                id * 10, id, now, now, now);
        return id;
    }
}
