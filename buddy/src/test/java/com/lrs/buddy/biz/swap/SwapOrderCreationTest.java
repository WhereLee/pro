package com.lrs.buddy.biz.swap;

import com.lrs.buddy.biz.swap.order.OrderState;
import com.lrs.buddy.biz.swap.provision.DeviceProvisionService;
import com.lrs.buddy.biz.swap.service.SwapLedgerService;
import com.lrs.buddy.biz.swap.service.SwapOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 建单：guard 链、预占、步骤生成、事件流与拒绝补偿。
 *
 * 断言的重点不是"能建单"，而是**约束真的住在 DB 里**：
 * 一人一单靠 active_user 生成列、抢仓靠 active_slot 唯一索引、额度靠带条件的 UPDATE 影响行数。
 * 所以每个拒绝用例都反查了库里到底留下了什么——
 * "REJECTED 且零物理动作"是文档承诺，只有查 iot_command / reservation / right 流水才能证明它成立。
 */
@SpringBootTest
@ActiveProfiles("test")
class SwapOrderCreationTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String BATTERY_PRODUCT = "BAT-60V20AH";
    /**
     * 会员用计数生成而不是共用 V10 那一个：
     * B3 一人一单是表级约束，happy path 留下的在途单会让后面几个用例直接撞它，
     * 那时测的就不是被测 guard 而是用例之间的相互干拢。每个用例自带会员才算隔离。
     */
    private static final java.util.concurrent.atomic.AtomicLong MEMBER_SEQ =
            new java.util.concurrent.atomic.AtomicLong(900_000L);

    @Autowired
    private SwapOrderService orders;
    @Autowired
    private SwapLedgerService ledger;
    @Autowired
    private DeviceProvisionService provision;
    @Autowired
    private JdbcTemplate jdbc;

    private String cabinetNo;
    private long member;

    @BeforeEach
    void setUpCabinet() {
        member = seedMember(60, 0);
        cabinetNo = "CAB-ORD-" + System.nanoTime();
        var device = provision.register(PRODUCT_KEY, "CABO-" + System.nanoTime(), "订单测试柜");
        ledger.createCabinet(1L, PRODUCT_KEY, cabinetNo, device.deviceId(), 8, null, null);
        // 在线态由接入层按报文静默轮数写入（M1 已实现）；本用例聚焦建单，直接置 ONLINE。
        // 跨层的"设备真连上后 guard 才放行"由 M2 末的跨进程联跑用例承担，不在这里假造。
        jdbc.update("UPDATE iot_device SET online_state = 'ONLINE', last_seen_ts = ? WHERE id = ?",
                System.currentTimeMillis(), device.deviceRowId());
    }

    private void stockBatteries(String... socPerSlot) {
        int slotNo = 1;
        for (String soc : socPerSlot) {
            ledger.registerBattery(cabinetNo, slotNo++, "BAT-" + System.nanoTime() + "-" + slotNo,
                    BATTERY_PRODUCT, Integer.parseInt(soc), new BigDecimal("26.0"),
                    new BigDecimal("20.0"), new BigDecimal("60.0"));
        }
    }

    @Test
    @DisplayName("正常建单：AUTHORIZED + 6 步骤 + 2 预占 + 仓态同步 + 权益预占")
    void happyPathCreatesAuthorizedOrder() {
        stockBatteries("92", "88", "95");

        SwapOrderService.CreateResult result = orders.create(member, cabinetNo, "H5", "trace-happy");

        assertThat(result.rejectReasons()).as("应通过全部 guard").isEmpty();
        assertThat(result.state()).isEqualTo(OrderState.AUTHORIZED);
        assertThat(result.returnSlotNo()).isNotNull();
        assertThat(result.offerSlotNo()).isNotNull();

        List<Map<String, Object>> steps = jdbc.queryForList(
                "SELECT step_no, step_code, step_state, slot_no FROM swap_order_step WHERE order_id = ? "
                        + "ORDER BY step_no", result.orderId());
        assertThat(steps).hasSize(6);
        assertThat(steps).allMatch(row -> "PENDING".equals(row.get("step_state")));
        assertThat(steps.get(0).get("step_code")).isEqualTo("OPEN_RETURN");
        assertThat(steps.get(3).get("step_code")).isEqualTo("UNLOCK_OFFER");

        List<Map<String, Object>> reservations = jdbc.queryForList(
                "SELECT use_role, resv_state FROM swap_slot_reservation WHERE order_id = ?", result.orderId());
        assertThat(reservations).hasSize(2);
        assertThat(reservations).extracting(row -> String.valueOf(row.get("use_role")))
                .containsExactlyInAnyOrder("RETURN", "OFFER");

        // 预占 ⇔ 仓态：不一致就等于"仓看起来空着，其实已经被占"
        Integer reservedSlots = jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot "
                + "WHERE slot_state = 'RESERVED_ORDER' AND reserved_order_id = ?", Integer.class, result.orderId());
        assertThat(reservedSlots).isEqualTo(2);

        assertThat(jdbc.queryForObject("SELECT right_state FROM swap_order WHERE id = ?", String.class,
                result.orderId())).isEqualTo("OCCUPIED");
        assertThat(jdbc.queryForObject("SELECT times_occupied FROM swap_right_account WHERE member_id = ?",
                Integer.class, member)).isEqualTo(1);

        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT seq_no, event_type, from_state, to_state FROM swap_order_event WHERE order_id = ? "
                        + "ORDER BY seq_no", result.orderId());
        assertThat(events).as("事件流是状态的可重放来源，建单至少留下创建与授权两条")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(events.get(0).get("event_type")).isEqualTo("ORDER_CREATED");
        assertThat(events).anyMatch(row -> "GUARD_PASS".equals(row.get("event_type"))
                && OrderState.AUTHORIZED.name().equals(row.get("to_state")));
    }

    @Test
    @DisplayName("B3 一人一单：由 active_user 生成列拒绝，不是应用先查后判")
    void secondInFlightOrderIsBlockedByGeneratedColumn() {
        stockBatteries("92", "88", "95", "70");
        orders.create(member, cabinetNo, "H5", "trace-first");

        assertThatThrownBy(() -> orders.create(member, cabinetNo, "H5", "trace-second"))
                .as("同一用户不能在途两笔，且第二笔不留订单行（否则拒绝率指标会被刷单污染）")
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_order WHERE user_id = ?", Integer.class, member))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("guard 不过也要留 REJECTED 行，但必须是零物理动作、零预占")
    void rejectionLeavesNoSideEffects() {
        stockBatteries("30", "25");   // 全部低于站点 min_soc=80 → 无满电仓

        SwapOrderService.CreateResult result = orders.create(member, cabinetNo, "H5", "trace-nogood");

        assertThat(result.state()).isEqualTo(OrderState.REJECTED);
        assertThat(result.rejectReasons()).anyMatch(reason -> reason.startsWith("NO_OFFER_SLOT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot_reservation WHERE order_id = ?",
                Integer.class, result.orderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_right_transaction WHERE order_id = ?",
                Integer.class, result.orderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT times_occupied FROM swap_right_account WHERE member_id = ?",
                Integer.class, member)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iot_command WHERE biz_type = 'SWAP_ORDER' "
                + "AND biz_id = ?", Integer.class, result.orderId()))
                .as("REJECTED 的定义就是从未发出物理动作：有指令记录就说明实现漏了")
                .isZero();
        assertThat(jdbc.queryForObject("SELECT order_state FROM swap_order WHERE id = ?", String.class,
                result.orderId())).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("柜机离线时 guard 拒绝，并留下 DEVICE_OFFLINE 原因")
    void offlineCabinetIsRejected() {
        stockBatteries("95", "92");
        jdbc.update("UPDATE iot_device d SET online_state = 'OFFLINE' "
                + "WHERE d.id = (SELECT device_row_id FROM swap_cabinet WHERE cabinet_no = ?)", cabinetNo);

        SwapOrderService.CreateResult result = orders.create(member, cabinetNo, "H5", "trace-offline");

        assertThat(result.state()).isEqualTo(OrderState.REJECTED);
        assertThat(result.rejectReasons()).contains("DEVICE_OFFLINE");
    }

    @Test
    @DisplayName("权益不足时由 DB 条件判定并整体补偿回退，不留半占状态")
    void insufficientRightsCompensatesEverything() {
        member = seedMember(60, 60);   // 已用满：额度判断写在 WHERE 里，不由应用读余额判断
        stockBatteries("95", "92", "90");

        SwapOrderService.CreateResult result = orders.create(member, cabinetNo, "H5", "trace-noright");

        assertThat(result.state()).isEqualTo(OrderState.REJECTED);
        assertThat(result.rejectReasons()).containsExactly("RIGHTS_INSUFFICIENT_OR_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot_reservation WHERE order_id = ?",
                Integer.class, result.orderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot "
                + "WHERE slot_state = 'RESERVED_ORDER' AND reserved_order_id = ?", Integer.class, result.orderId()))
                .as("权益预占失败时仓不能被占住").isZero();
    }

    @Test
    @DisplayName("两单争同一柜：抢输的一方被拒，且不出现同仓双 ACTIVE 预占")
    void concurrentReservationNeverDoubleBooksASlot() {
        stockBatteries("99");   // 只有一个满电仓 → 两笔单只能有一笔拿到它
        long otherMember = seedMember(60, 0);

        SwapOrderService.CreateResult first = orders.create(member, cabinetNo, "H5", "trace-race-a");
        SwapOrderService.CreateResult second = orders.create(otherMember, cabinetNo, "H5", "trace-race-b");

        assertThat(first.state()).isEqualTo(OrderState.AUTHORIZED);
        assertThat(second.state()).as("满电仓只有一个，第二笔必然被拒").isEqualTo(OrderState.REJECTED);
        Integer activePerSlot = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT slot_id FROM swap_slot_reservation WHERE resv_state = 'ACTIVE' "
                        + "GROUP BY slot_id HAVING COUNT(*) > 1) t", Integer.class);
        assertThat(activePerSlot)
                .as("active_slot 生成列唯一索引必须保证一个仓至多一条 ACTIVE 预占").isZero();
    }

    private long seedMember(int timesTotal, int timesUsed) {
        long id = MEMBER_SEQ.incrementAndGet();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO member_user (id, member_no, nickname, realname_state, member_state, "
                        + "register_source, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'VERIFIED', 'NORMAL', 'H5', ?,?, 0, 0, 1)",
                id, "M" + id, "建单测试会员", now, now);
        jdbc.update("INSERT INTO swap_right_account (id, member_id, plan_id, times_total, times_used, times_occupied, "
                        + "valid_from, valid_until, freeze_state, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?, ?, 1, ?, ?, 0, ?, '2099-12-31 00:00:00', 'NORMAL', ?, ?, 0, 0, 1)",
                id * 10, id, timesTotal, timesUsed, now, now, now);
        return id;
    }
}
