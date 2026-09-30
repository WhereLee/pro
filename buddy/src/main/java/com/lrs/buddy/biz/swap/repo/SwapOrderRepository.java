package com.lrs.buddy.biz.swap.repo;

import com.lrs.buddy.biz.swap.alloc.SlotAllocator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 订单主线的数据访问（JdbcTemplate，不用 ORM）。
 *
 * 为什么这一层坚持写 SQL：
 * 建单要在同一条语句里完成"预占权益 + 判断够不够"（CAS 式 UPDATE 带条件），
 * 抢仓位要靠生成列唯一索引 `active_slot` 报错来判定输赢，
 * 事件流是 append-only 且不能带逻辑删除列。这些形态用 ORM 表达要么写不出来、
 * 要么写成"先查后改"——而先查后改正是并发事故的产生器。
 */
@Repository
public class SwapOrderRepository {

    private final JdbcTemplate jdbc;

    public SwapOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 订单头（够服务层做 guard 与推进用，字段刻意精简；null 表示不存在）。 */
    public record OrderRow(Long id, String orderNo, Long userId, Long siteId, Long cabinetId,
                           Integer returnSlotNo, Integer offerSlotNo, String state, String rightState,
                           Long tenantId) {
    }

    public record MemberRow(Long id, String state, Integer riskFlag) {
    }

    public record AccountRow(Long id, Long memberId, Integer timesTotal, Integer timesUsed, Integer timesOccupied,
                             LocalDateTime validUntil, String freezeState, Long planId) {
    }

    public record CabinetRow(Long id, Long siteId, String cabinetNo, String cabinetState, Long deviceRowId) {
    }

    public record ThresholdRow(int minSoc, BigDecimal maxAllocTemp, int telemetryFreshSec, BigDecimal siteTempOverride,
                               Integer siteMinSocOverride) {
    }

    public record CreateOrder(long id, String orderNo, long userId, long siteId, long cabinetId, Integer returnSlotNo,
                              Integer offerSlotNo, Long returnBatteryId, Long offerBatteryId, Long planId,
                              String state, String rightState, String evidenceLevel, String source, String traceId,
                              LocalDateTime now, Long tenantId, long deadlineTs) {
    }

    public Long insertCreatedOrder(CreateOrder c) {
        jdbc.update("""
                INSERT INTO swap_order (id, order_no, user_id, site_id, cabinet_id, return_slot_no, offer_slot_no,
                        return_battery_id, offer_battery_id, plan_id, order_state, right_state, evidence_level,
                        source, trace_id, deadline_at, deadline_ts, create_time, update_time, version, del_flag, tenant_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 0, 0, ?)
                """, c.id(), c.orderNo(), c.userId(), c.siteId(), c.cabinetId(), c.returnSlotNo(), c.offerSlotNo(),
                c.returnBatteryId(), c.offerBatteryId(), c.planId(), c.state(), c.rightState(), c.evidenceLevel(),
                c.source(), c.traceId(), Timestamp.valueOf(c.now()), c.deadlineTs(), Timestamp.valueOf(c.now()),
                Timestamp.valueOf(c.now()), c.tenantId());
        return c.id();
    }

    /**
     * 状态迁移：带 fromState 谓词的 CAS。
     *
     * 返回 false 表示这条订单在并发下已被别人改过（超时任务、用户重复提交、人工干预同时命中）。
     * 调用方必须把 false 当成"事实已变"处理而不是重试覆盖，否则两次迁移都会生效。
     */
    public boolean transition(long orderId, String fromState, String toState, LocalDateTime now, long deadlineTs,
                              String terminalReason, boolean setAuthorizedAt, boolean setReturnedAt,
                              boolean setTakenAt, boolean setSettledAt, boolean setClosedAt) {
        StringBuilder sql = new StringBuilder("UPDATE swap_order SET order_state = ?, update_time = ?, "
                + "deadline_at = ?, deadline_ts = ?, version = version + 1");
        if (terminalReason != null) {
            sql.append(", terminal_reason = ?");
        }
        if (setAuthorizedAt) {
            sql.append(", authorized_at = ?");
        }
        if (setReturnedAt) {
            sql.append(", returned_at = ?");
        }
        if (setTakenAt) {
            sql.append(", taken_at = ?");
        }
        if (setSettledAt) {
            sql.append(", settled_at = ?");
        }
        if (setClosedAt) {
            sql.append(", closed_at = ?");
        }
        sql.append(" WHERE id = ? AND order_state = ?");

        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(toState);
        args.add(Timestamp.valueOf(now));
        args.add(Timestamp.valueOf(now.plusSeconds(secondsUntil(toState))));
        args.add(deadlineTs);
        if (terminalReason != null) {
            args.add(terminalReason);
        }
        if (setAuthorizedAt || setReturnedAt || setTakenAt || setSettledAt || setClosedAt) {
            // 这些时间戳列语义相同（进入该阶段的时刻），一次 now 够用
            for (int i = 0; i < count(setAuthorizedAt, setReturnedAt, setTakenAt, setSettledAt, setClosedAt); i++) {
                args.add(Timestamp.valueOf(now));
            }
        }
        args.add(orderId);
        args.add(fromState);
        return jdbc.update(sql.toString(), args.toArray()) == 1;
    }

    private static int count(boolean... flags) {
        int n = 0;
        for (boolean flag : flags) {
            if (flag) {
                n++;
            }
        }
        return n;
    }

    private static int secondsUntil(String toState) {
        try {
            return com.lrs.buddy.biz.swap.order.OrderState.valueOf(toState).maxDwellSeconds();
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    public boolean updateRightState(long orderId, String rightState) {
        return jdbc.update("UPDATE swap_order SET right_state = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                rightState, orderId) == 1;
    }

    public OrderRow findOrder(long orderId) {
        List<OrderRow> rows = jdbc.query("SELECT id, order_no, user_id, site_id, cabinet_id, return_slot_no, "
                        + "offer_slot_no, order_state, right_state, tenant_id FROM swap_order WHERE id = ?",
                (rs, i) -> new OrderRow(rs.getLong("id"), rs.getString("order_no"), rs.getLong("user_id"),
                        rs.getLong("site_id"), rs.getLong("cabinet_id"), nullableInt(rs, "return_slot_no"),
                        nullableInt(rs, "offer_slot_no"), rs.getString("order_state"), rs.getString("right_state"),
                        rs.getLong("tenant_id")),
                orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int countInfightByUser(long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM swap_order WHERE user_id = ? AND active_user IS NOT NULL",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    // ---------------- 步骤 ----------------

    public void insertStep(long id, long orderId, int stepNo, String stepCode, String expectCmd, String expectEvent,
                           Integer slotNo, Long batteryId, LocalDateTime now, int deadlineSeconds, long tenantId) {
        jdbc.update("""
                INSERT INTO swap_order_step (id, order_id, step_no, step_code, expect_cmd, expect_event, slot_no,
                        battery_id, step_state, deadline_at, deadline_ts, create_time, update_time, version, del_flag, tenant_id)
                VALUES (?,?,?,?,?,?,?,?, 'PENDING', ?,?, ?,?, 0, 0, ?)
                """, id, orderId, stepNo, stepCode, expectCmd, expectEvent, slotNo, batteryId,
                Timestamp.valueOf(now.plusSeconds(deadlineSeconds)),
                now.plusSeconds(deadlineSeconds).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                Timestamp.valueOf(now), Timestamp.valueOf(now), tenantId);
    }

    public List<Map<String, Object>> steps(long orderId) {
        return jdbc.queryForList("SELECT step_no, step_code, expect_cmd, expect_event, slot_no, battery_id, step_state,"
                + " deadline_ts FROM swap_order_step WHERE order_id = ? ORDER BY step_no", orderId);
    }

    public boolean advanceStep(long orderId, int stepNo, String fromState, String toState, String cmdId,
                               String sessionId, String factsJson, LocalDateTime now) {
        return jdbc.update("""
                UPDATE swap_order_step SET step_state = ?, update_time = ?, version = version + 1,
                       dispatch_cmd_id = COALESCE(?, dispatch_cmd_id),
                       session_id = COALESCE(?, session_id),
                       facts_json = COALESCE(?, facts_json),
                       started_at = COALESCE(started_at, ?),
                       finished_at = CASE WHEN ? IN ('PHYSICS_DONE','VERIFIED','FAILED','SKIPPED') THEN ? ELSE finished_at END
                WHERE order_id = ? AND step_no = ? AND step_state = ?
                """, toState, Timestamp.valueOf(now), cmdId, sessionId, factsJson, Timestamp.valueOf(now),
                toState, Timestamp.valueOf(now), orderId, stepNo, fromState) == 1;
    }

    // ---------------- 事件流（append-only 事实源） ----------------

    public void appendEvent(long id, long orderId, String eventType, String fromState, String toState, String source,
                            String msgId, String cmdId, String sessionId, Integer slotNo, Long batteryId,
                            String detail, LocalDateTime now, Long operatorId, String traceId, long tenantId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(seq_no), 0) FROM swap_order_event WHERE order_id = ?",
                Integer.class, orderId);
        jdbc.update("""
                INSERT INTO swap_order_event (id, order_id, seq_no, event_type, from_state, to_state, source,
                        msg_id, cmd_id, session_id, slot_no, battery_id, detail, occurred_at, ts_millis,
                        operator_id, trace_id, create_time, tenant_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?)
                """, id, orderId, (max == null ? 0 : max) + 1, eventType, fromState, toState, source, msgId, cmdId,
                sessionId, slotNo, batteryId, detail, Timestamp.valueOf(now),
                now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), operatorId, traceId,
                Timestamp.valueOf(now), tenantId);
    }

    public List<Map<String, Object>> events(long orderId) {
        return jdbc.queryForList("SELECT seq_no, event_type, from_state, to_state, source, detail "
                + "FROM swap_order_event WHERE order_id = ? ORDER BY seq_no", orderId);
    }

    // ---------------- 仓位预占 ----------------

    /**
     * 抢仓位。返回 false 表示这个仓位已被别的订单预占（唯一索引 active_slot 拒绝）。
     *
     * 这里必须"直接 INSERT 看是否报唯一冲突"，绝不能先 SELECT 再 INSERT：
     * 先查后改在两个并发请求下会同时看到"没被占"，然后双双插入 ——
     * 结果是同一仓开了两次门，这是本项目最不能发生的事。
     */
    public boolean reserveSlot(long id, long slotId, long orderId, String useRole, LocalDateTime now, long tenantId) {
        try {
            jdbc.update("""
                    INSERT INTO swap_slot_reservation (id, slot_id, order_id, use_role, resv_state, reserved_at,
                            create_time, update_time, version, del_flag, tenant_id)
                    VALUES (?,?,?,?, 'ACTIVE', ?,?, ?, 0, 0, ?)
                    """, id, slotId, orderId, useRole, Timestamp.valueOf(now), Timestamp.valueOf(now),
                    Timestamp.valueOf(now), tenantId);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 预占成功后把仓态推到 RESERVED_ORDER。
     *
     * 不做这一步不会出安全事故（抢仓靠唯一索引），但会**误拒**：
     * 下一单的分配候选仍把这个仓当成可用，选中后才发现抢不到，
     * 于是本来能成的单被拒——用户看到"没仓"而柜机里其实有。它与 {@link #restoreSlotsOf} 必须成对调用。
     */
    public int markSlotsReserved(long slotIdA, long slotIdB, long orderId, LocalDateTime now) {
        return jdbc.update("UPDATE swap_slot SET slot_state = 'RESERVED_ORDER', reserved_order_id = ?, "
                        + "update_time = ?, version = version + 1 WHERE id IN (?,?) AND del_flag = 0",
                orderId, Timestamp.valueOf(now), slotIdA, slotIdB);
    }

    /** 释放预占后回滚仓态：有电池回 IDLE_CHARGING，无电池回 IDLE_EMPTY。 */
    public int restoreSlotsOf(long orderId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE swap_slot SET slot_state = CASE WHEN battery_id IS NULL THEN 'IDLE_EMPTY' ELSE 'IDLE_CHARGING' END,
                       reserved_order_id = NULL, update_time = ?, version = version + 1
                WHERE reserved_order_id = ?
                """, Timestamp.valueOf(now), orderId);
    }

    public List<Map<String, Object>> reservations(long orderId) {
        return jdbc.queryForList("SELECT slot_id, use_role, resv_state FROM swap_slot_reservation WHERE order_id = ?",
                orderId);
    }

    /**
     * 释放预占。置 RELEASED 而不是删行：
     * 删行会让"这个仓曾被谁占过"这件事失去记录，而补偿与对账全靠它。
     */
    public int releaseReservations(long orderId, String reason, LocalDateTime now) {
        return jdbc.update("UPDATE swap_slot_reservation SET resv_state = 'RELEASED', released_at = ?, "
                + "release_reason = ?, update_time = ? WHERE order_id = ? AND resv_state = 'ACTIVE'",
                Timestamp.valueOf(now), reason, Timestamp.valueOf(now), orderId);
    }

    // ---------------- 会员 / 权益 / 柜机 / 候选仓 ----------------

    public MemberRow findMember(long userId) {
        List<MemberRow> rows = jdbc.query("SELECT id, member_state, risk_flag FROM member_user WHERE id = ? "
                        + "AND del_flag = 0", (rs, i) -> new MemberRow(rs.getLong("id"),
                rs.getString("member_state"), rs.getInt("risk_flag")), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AccountRow findAccount(long userId) {
        List<AccountRow> rows = jdbc.query("SELECT id, member_id, times_total, times_used, times_occupied, "
                        + "valid_until, freeze_state, plan_id FROM swap_right_account WHERE member_id = ? AND del_flag = 0",
                (rs, i) -> new AccountRow(rs.getLong("id"), rs.getLong("member_id"), rs.getInt("times_total"),
                        rs.getInt("times_used"), rs.getInt("times_occupied"),
                        rs.getObject("valid_until") == null ? null : rs.getTimestamp("valid_until").toLocalDateTime(),
                        rs.getString("freeze_state"),
                        rs.getObject("plan_id") == null ? null : rs.getLong("plan_id")),
                userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 预占一次权益：条件全写在 WHERE 里，影响行数 0 就是"不够"。
     *
     * 这是 I3 的前半段：预占与订单写入同事务，且额度判定由 DB 而不是应用代码给出结论。
     * 应用先读余额再判断，在并发下会双双通过（两个请求都读到剩 1 次）。
     */
    public boolean occupyRight(long accountId) {
        return jdbc.update("UPDATE swap_right_account SET times_occupied = times_occupied + 1, "
                        + "update_time = CURRENT_TIMESTAMP, version = version + 1 "
                        + "WHERE id = ? AND freeze_state = 'NORMAL' AND valid_until > CURRENT_TIMESTAMP "
                        + "AND times_total - times_used - times_occupied >= 1", accountId) == 1;
    }

    public boolean releaseRightOccupation(long accountId) {
        return jdbc.update("UPDATE swap_right_account SET times_occupied = times_occupied - 1, "
                        + "update_time = CURRENT_TIMESTAMP, version = version + 1 "
                        + "WHERE id = ? AND times_occupied > 0", accountId) == 1;
    }

    public void insertRightTransaction(long id, long memberId, long accountId, Long orderId, String kind,
                                       int deltaTimes, Integer balanceAfter, LocalDateTime now, String reason,
                                       String traceId, long tenantId) {
        jdbc.update("""
                INSERT INTO swap_right_transaction (id, member_id, account_id, order_id, kind, delta_times,
                        balance_after, occurred_at, ts_millis, reason, trace_id, create_time, tenant_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?, ?)
                """, id, memberId, accountId, orderId, kind, deltaTimes, balanceAfter, Timestamp.valueOf(now),
                now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), reason, traceId,
                Timestamp.valueOf(now), tenantId);
    }

    public CabinetRow findCabinet(String cabinetNo) {
        List<CabinetRow> rows = jdbc.query("SELECT id, site_id, cabinet_no, cabinet_state, device_row_id "
                        + "FROM swap_cabinet WHERE cabinet_no = ? AND del_flag = 0",
                (rs, i) -> new CabinetRow(rs.getLong("id"), rs.getLong("site_id"), rs.getString("cabinet_no"),
                        rs.getString("cabinet_state"), rs.getLong("device_row_id")),
                cabinetNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String deviceOnlineState(long deviceRowId) {
        List<String> list = jdbc.queryForList("SELECT online_state FROM iot_device WHERE id = ? AND enabled = 1 "
                + "AND del_flag = 0", String.class, deviceRowId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 阈值：产品默认值 + 站点收紧（站点只能收紧，DB CHECK 已钉住方向）。 */
    public ThresholdRow thresholds(long siteId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(s.product_min_soc, 80) AS min_soc,
                       COALESCE(s.product_max_alloc_temp, 45.0) AS max_alloc_temp,
                       COALESCE(p.telemetry_fresh_sec, 60) AS fresh_sec,
                       s.site_max_alloc_temp AS site_temp,
                       s.site_min_soc AS site_min_soc
                FROM swap_site s JOIN iot_product p ON p.product_key = s.product_key
                WHERE s.id = ? AND s.del_flag = 0
                """, (rs, i) -> new ThresholdRow(rs.getInt("min_soc"), rs.getBigDecimal("max_alloc_temp"),
                        rs.getInt("fresh_sec"), rs.getBigDecimal("site_temp"),
                        rs.getObject("site_min_soc") == null ? null : rs.getInt("site_min_soc")),
                siteId);
    }

    /** 预占表引用的是 swap_slot.id（仓号仅在柜机内唯一，跳柜会撞车）。 */
    public Long slotRowId(long cabinetId, int slotNo) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM swap_slot WHERE cabinet_id = ? AND slot_no = ? "
                + "AND del_flag = 0", Long.class, cabinetId, slotNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Long batteryRowId(String batteryCode) {
        if (batteryCode == null) {
            return null;
        }
        List<Long> ids = jdbc.queryForList("SELECT id FROM swap_battery WHERE battery_code = ? AND del_flag = 0",
                Long.class, batteryCode);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 候选仓位（含电池投影）。一次查全，避免"逐仓查询"的 N+1 ——
     * 8 仓柜机建单时打 8 次库，在并发下会把锁窗口拉得比重复计算还长。
     */
    public List<SlotAllocator.Candidate> candidates(long cabinetId) {
        return jdbc.query("""
                SELECT sl.slot_no, sl.slot_state, sl.last_temp, sl.last_full_at, sl.last_detected_at,
                       sl.fault_code, sl.disabled_flag,
                       b.battery_code, b.battery_state, b.location_state, b.soc, b.soh, b.cycle_count, b.fault_code AS batt_fault
                FROM swap_slot sl LEFT JOIN swap_battery b ON b.id = sl.battery_id AND b.del_flag = 0
                WHERE sl.cabinet_id = ? AND sl.del_flag = 0
                """, (rs, i) -> toCandidate(rs), cabinetId);
    }

    private static SlotAllocator.Candidate toCandidate(ResultSet rs) throws SQLException {
        String batteryCode = rs.getString("battery_code");
        return new SlotAllocator.Candidate(rs.getInt("slot_no"), rs.getString("slot_state"), batteryCode,
                rs.getString("battery_state"), rs.getString("location_state"),
                rs.getObject("soc") == null ? null : rs.getInt("soc"),
                rs.getBigDecimal("soh"),
                rs.getObject("cycle_count") == null ? null : rs.getInt("cycle_count"),
                rs.getBigDecimal("last_temp"),
                rs.getTimestamp("last_full_at") == null ? null : rs.getTimestamp("last_full_at").toLocalDateTime(),
                rs.getTimestamp("last_detected_at") == null ? null : rs.getTimestamp("last_detected_at").toLocalDateTime(),
                firstNonBlank(rs.getString("fault_code"), rs.getString("batt_fault")),
                rs.getInt("disabled_flag"));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** 上一单取走的电池（同人连续惩罚的输入）。没有历史返回 null。 */
    public SlotAllocator.LastAllocation lastAllocationOf(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.battery_code, o.offer_slot_no FROM swap_order o
                LEFT JOIN swap_battery b ON b.id = o.offer_battery_id
                WHERE o.user_id = ? AND o.order_state = 'COMPLETED'
                ORDER BY o.settled_at DESC LIMIT 1
                """, userId);
        if (rows.isEmpty()) {
            return null;
        }
        Object code = rows.get(0).get("battery_code");
        Object slot = rows.get(0).get("offer_slot_no");
        return new SlotAllocator.LastAllocation(code == null ? null : code.toString(),
                slot == null ? null : ((Number) slot).intValue());
    }

    public Duration freshnessOf(int seconds) {
        return Duration.ofSeconds(Math.max(30, seconds));
    }

    /** 单号：SW + 日期 + 序列位。可读且能一眼看出日期。 */
    public static String newOrderNo(LocalDateTime now) {
        return String.format("SW%s%06d", now.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")),
                java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
