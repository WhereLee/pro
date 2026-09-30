package com.lrs.buddy.biz.swap.repo;

import com.lrs.buddy.biz.swap.alloc.SlotAllocator;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
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

/** 流水 id 生成：不依赖 MyBatis 雪花工具的地方用这个最小实现（表列是 BIGINT 自增以外手工填）。 */
class IdWorkerLike {
    static long next() {
        return com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
    }
}

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
                           Integer returnSlotNo, Integer offerSlotNo, Long returnBatteryId, Long offerBatteryId,
                           String state, String rightState, Long tenantId) {
    }

    /** 柜机对应的接入设备行：下发指令需要 productKey / deviceId / 设台账 id。 */
    public record CabinetDeviceRow(Long deviceRowId, String productKey, String deviceId) {
    }

    public record MemberRow(Long id, String state, Integer riskFlag, String realnameState) {
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
        return orderForRow(" WHERE id = ?", orderId);
    }

    /**
     * 按单号查订单，**包含已终的订单**。
     *
     * 对账与柜侧陈述比对必须能引用到已完成的历史单：柜机上报 swap_result 往往在云端已结算之后，
     * 只查在途单会把这条本来可比对的陈述当成"无关事件"，差异就漏了。
     */
    public OrderRow findByOrderNo(String orderNo) {
        return orderForRow(" WHERE order_no = ?", orderNo);
    }

    private OrderRow orderForRow(String whereClause, Object arg) {
        List<OrderRow> rows = jdbc.query("SELECT id, order_no, user_id, site_id, cabinet_id, return_slot_no, "
                        + "offer_slot_no, return_battery_id, offer_battery_id, order_state, right_state, tenant_id "
                        + "FROM swap_order" + whereClause,
                (rs, i) -> new OrderRow(rs.getLong("id"), rs.getString("order_no"), rs.getLong("user_id"),
                        rs.getLong("site_id"), rs.getLong("cabinet_id"), nullableInt(rs, "return_slot_no"),
                        nullableInt(rs, "offer_slot_no"),
                        rs.getObject("return_battery_id") == null ? null : rs.getLong("return_battery_id"),
                        rs.getObject("offer_battery_id") == null ? null : rs.getLong("offer_battery_id"),
                        rs.getString("order_state"), rs.getString("right_state"), rs.getLong("tenant_id")),
                arg);
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
        List<MemberRow> rows = jdbc.query("SELECT id, member_state, risk_flag, realname_state FROM member_user "
                        + "WHERE id = ? AND del_flag = 0",
                (rs, i) -> new MemberRow(rs.getLong("id"), rs.getString("member_state"), rs.getInt("risk_flag"),
                        rs.getString("realname_state")), userId);
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

    // ---------------- 反查与超时驱动需要的读写 ----------------

    public record DoorProbe(String doorState, String lockState, LocalDateTime lastDetectedAt) {
    }

    public record ShadowRow(String reportedJson, LocalDateTime syncedAt) {
    }

    /**
     * 事件到达时回写仓门磁投影。
     *
     * 不写就会有一个双重代价：一是不变式 I7（台账与事实一致）无从成立，
     * 二是超时时的反查只能看到一个永远为 CLOSED 的投影，把"门其实开了"误判成"门没开"而重发开仓。
     */
    public void setSlotDoor(long cabinetId, int slotNo, String doorState, String lockState, LocalDateTime now) {
        jdbc.update("UPDATE swap_slot SET door_state = ?, lock_state = ?, last_detected_at = ?, update_time = ?, "
                        + "version = version + 1 WHERE cabinet_id = ? AND slot_no = ? AND del_flag = 0",
                doorState, lockState, Timestamp.valueOf(now), Timestamp.valueOf(now), cabinetId, slotNo);
    }

    /** 读台账投影的门磁作为反查的第一个来源（它能告诉我们要的是"云端已知道什么"，不是新事实）。 */
    public DoorProbe probeSlotDoor(long cabinetId, int slotNo) {
        List<DoorProbe> rows = jdbc.query("SELECT door_state, lock_state, last_detected_at FROM swap_slot "
                        + "WHERE cabinet_id = ? AND slot_no = ? AND del_flag = 0",
                (rs, i) -> new DoorProbe(rs.getString("door_state"), rs.getString("lock_state"),
                        rs.getTimestamp("last_detected_at") == null ? null
                                : rs.getTimestamp("last_detected_at").toLocalDateTime()),
                cabinetId, slotNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 已下发次数：直接数 iot_command 而不是在步骤表加一个 attempts 列。
     * 指令表已经是事实源，再加一个计数列就是第二份真相——两者早晚会不一致。
     */
    public int countDispatched(long orderId, int stepNo) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM iot_command WHERE biz_type = 'SWAP_ORDER' "
                + "AND biz_id = ? AND step_no = ?", Integer.class, orderId, stepNo);
        return n == null ? 0 : n;
    }

    /** 扫到期的在途订单（走 idx_ord_scan (order_state, deadline_ts)）。 */
    public List<OrderRow> scanDueOrders(long nowTs, int limit) {
        return jdbc.query("SELECT id, order_no, user_id, site_id, cabinet_id, return_slot_no, offer_slot_no, "
                        + "return_battery_id, offer_battery_id, order_state, right_state, tenant_id FROM swap_order "
                        + "WHERE active_user IS NOT NULL AND deadline_ts IS NOT NULL AND deadline_ts <= ? "
                        + "ORDER BY deadline_ts LIMIT " + Math.max(1, Math.min(limit, 200)),
                (rs, i) -> new OrderRow(rs.getLong("id"), rs.getString("order_no"), rs.getLong("user_id"),
                        rs.getLong("site_id"), rs.getLong("cabinet_id"), nullableInt(rs, "return_slot_no"),
                        nullableInt(rs, "offer_slot_no"),
                        rs.getObject("return_battery_id") == null ? null : rs.getLong("return_battery_id"),
                        rs.getObject("offer_battery_id") == null ? null : rs.getLong("offer_battery_id"),
                        rs.getString("order_state"), rs.getString("right_state"), rs.getLong("tenant_id")),
                nowTs);
    }

    /** 影子上报态写入（只由 QUERY_STATUS 应答驱动）。 */
    public void upsertShadowReported(long deviceRowId, String reportedJson, LocalDateTime now, long tenantId) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM iot_shadow WHERE device_row_id = ? AND del_flag = 0",
                Integer.class, deviceRowId);
        if (exists == null || exists == 0) {
            jdbc.update("INSERT INTO iot_shadow (id, device_row_id, reported_json, desired_ver, reported_ver, "
                            + "sync_state, synced_at, create_time, update_time, version, del_flag, tenant_id) "
                            + "VALUES (?,?,?, 0, 1, 'PENDING', ?,?, ?, 0, 0, ?)",
                    IdWorker.getId(), deviceRowId, reportedJson, Timestamp.valueOf(now), Timestamp.valueOf(now),
                    Timestamp.valueOf(now), tenantId);
            return;
        }
        jdbc.update("UPDATE iot_shadow SET reported_json = ?, reported_ver = reported_ver + 1, synced_at = ?, "
                + "update_time = ?, version = version + 1 WHERE device_row_id = ? AND del_flag = 0",
                reportedJson, Timestamp.valueOf(now), Timestamp.valueOf(now), deviceRowId);
    }

    public ShadowRow shadowReported(long deviceRowId) {
        List<ShadowRow> rows = jdbc.query("SELECT reported_json, synced_at FROM iot_shadow WHERE device_row_id = ? "
                        + "AND del_flag = 0", (rs, i) -> new ShadowRow(rs.getString("reported_json"),
                rs.getTimestamp("synced_at") == null ? null : rs.getTimestamp("synced_at").toLocalDateTime()),
                deviceRowId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void markBatteryPendingPickup(long batteryId, String reason, LocalDateTime now) {
        jdbc.update("UPDATE swap_battery SET battery_state = 'PENDING_PICKUP', isolated_reason = ?, update_time = ?, "
                + "version = version + 1 WHERE id = ?", reason, Timestamp.valueOf(now), batteryId);
    }

    public void markSelfResumeUsed(long orderId) {
        jdbc.update("UPDATE swap_order SET self_resume_used = 1, update_time = CURRENT_TIMESTAMP WHERE id = ?", orderId);
    }

    /** B2：用户自助恢复只能用一次，第二次就必须转人工。 */
    public boolean selfResumeUsed(long orderId) {
        Integer used = jdbc.queryForObject("SELECT self_resume_used FROM swap_order WHERE id = ?", Integer.class, orderId);
        return used != null && used == 1;
    }

    public String lockSlotForSafety(long cabinetId, int slotNo, String reason) {
        jdbc.update("UPDATE swap_slot SET slot_state = 'ISOLATED', fault_code = ?, update_time = CURRENT_TIMESTAMP "
                + "WHERE cabinet_id = ? AND slot_no = ?", reason, cabinetId, slotNo);
        return reason;
    }

    /** 设备行 id（影子与反查以设备为键）。 */
    public Long deviceRowOfCabinet(long cabinetId) {
        List<Long> ids = jdbc.queryForList("SELECT device_row_id FROM swap_cabinet WHERE id = ?", Long.class, cabinetId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Map<String, Object> commandByCode(long orderId, String cmdCode) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT cmd_id, cmd_state, reply_json, session_id "
                + "FROM iot_command WHERE biz_type = 'SWAP_ORDER' AND biz_id = ? AND cmd_code = ? ORDER BY id DESC "
                + "LIMIT 1", orderId, cmdCode);
        return rows.isEmpty() ? null : rows.get(0);
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

    // ---------------- 事件驱动流程需要的读写 ----------------

    /** 按接入设备找它的在途订单（一个柜机同时至多一单在处理物理动作）。 */
    public OrderRow findInfightByDevice(long deviceRowId) {
        List<OrderRow> rows = jdbc.query("SELECT o.id, o.order_no, o.user_id, o.site_id, o.cabinet_id, "
                        + "o.return_slot_no, o.offer_slot_no, o.return_battery_id, o.offer_battery_id, "
                        + "o.order_state, o.right_state, o.tenant_id "
                        + "FROM swap_order o JOIN swap_cabinet c ON c.id = o.cabinet_id "
                        + "WHERE c.device_row_id = ? AND o.active_user IS NOT NULL ORDER BY o.id DESC LIMIT 1",
                (rs, i) -> new OrderRow(rs.getLong("id"), rs.getString("order_no"), rs.getLong("user_id"),
                        rs.getLong("site_id"), rs.getLong("cabinet_id"), nullableInt(rs, "return_slot_no"),
                        nullableInt(rs, "offer_slot_no"),
                        rs.getObject("return_battery_id") == null ? null : rs.getLong("return_battery_id"),
                        rs.getObject("offer_battery_id") == null ? null : rs.getLong("offer_battery_id"),
                        rs.getString("order_state"), rs.getString("right_state"), rs.getLong("tenant_id")),
                deviceRowId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> step(long orderId, int stepNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT step_no, step_code, step_state, slot_no, battery_id,"
                        + " facts_json, dispatch_cmd_id, session_id FROM swap_order_step WHERE order_id = ? AND step_no = ?",
                orderId, stepNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 步骤事实只追写、不改状态。
     *
     * S2/S5 期望的是**两个事实**（battery_detected 与 door_close），它们可以乱序到达：
     * 先到的存进 facts_json、后到的才推迁移。要求设备按顺序上报是把真实世界当成理想情况，
     * 4G 丢一个包重投后顺序就变了。
     */
    public void setStepFacts(long orderId, int stepNo, String factsJson, LocalDateTime now) {
        jdbc.update("UPDATE swap_order_step SET facts_json = ?, update_time = ?, version = version + 1 "
                + "WHERE order_id = ? AND step_no = ?", factsJson, Timestamp.valueOf(now), orderId, stepNo);
    }

    public CabinetDeviceRow deviceOfCabinet(long cabinetId) {
        List<CabinetDeviceRow> rows = jdbc.query("SELECT c.device_row_id, d.product_key, d.device_id "
                        + "FROM swap_cabinet c JOIN iot_device d ON d.id = c.device_row_id WHERE c.id = ?",
                (rs, i) -> new CabinetDeviceRow(rs.getLong("device_row_id"), rs.getString("product_key"),
                        rs.getString("device_id")),
                cabinetId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("柜机未绑定接入设备：cabinetId=" + cabinetId);
        }
        return rows.get(0);
    }

    /**
     * 事件幂等（I6）：靠 `uk_ededup (order_id, event_type, msg_id)` 判重。
     * 同样不能先查后写；false = 已处理过，调用方必须直接返回而不是"当作异常"。
     */
    public boolean insertEventDedup(long id, long orderId, String eventType, String msgId, Integer slotNo,
                                     LocalDateTime now, long tenantId) {
        try {
            jdbc.update("INSERT INTO swap_event_dedup (id, order_id, event_type, msg_id, slot_no, occurred_at, "
                    + "create_time, tenant_id) VALUES (?,?,?,?,?,?,?, ?)",
                    id, orderId, eventType, msgId, slotNo, Timestamp.valueOf(now), Timestamp.valueOf(now), tenantId);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    public Map<String, Object> batteryByCode(String code) {
        List<Map<String, Object>> rows = code == null ? List.of()
                : jdbc.queryForList("SELECT id, battery_state, holder_user_id, current_slot_id, soc, soh, fault_code,"
                + " location_state FROM swap_battery WHERE battery_code = ? AND del_flag = 0", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> batteryById(long batteryId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, battery_code, battery_state, holder_user_id,"
                + " current_slot_id, soc, soh, cycle_count FROM swap_battery WHERE id = ? AND del_flag = 0", batteryId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void moveBattery(long batteryId, String state, Long cabinetId, Long slotId, Long holderUserId,
                            String locationState, LocalDateTime now) {
        jdbc.update("UPDATE swap_battery SET battery_state = ?, current_cabinet_id = ?, current_slot_id = ?, "
                        + "holder_user_id = ?, location_state = ?, last_report_at = ?, update_time = ?, "
                        + "version = version + 1 WHERE id = ?",
                state, cabinetId, slotId, holderUserId, locationState, Timestamp.valueOf(now), Timestamp.valueOf(now),
                batteryId);
    }

    /** 关用户在途绑定（旧电池不再属于他）与电池侧绑定，两者必须同时关。 */
    public int closeActiveBindings(Long userId, Long batteryId, String reason, LocalDateTime now) {
        if (userId != null) {
            jdbc.update("UPDATE swap_battery_binding SET bind_state = 'HIST', end_at = ?, end_reason = ?, "
                    + "update_time = ?, version = version + 1 WHERE active_user IS NOT NULL AND user_id = ?",
                    Timestamp.valueOf(now), reason, Timestamp.valueOf(now), userId);
        }
        if (batteryId != null) {
            jdbc.update("UPDATE swap_battery_binding SET bind_state = 'HIST', end_at = ?, end_reason = ?, "
                    + "update_time = ?, version = version + 1 WHERE active_battery IS NOT NULL AND battery_id = ?",
                    Timestamp.valueOf(now), reason, Timestamp.valueOf(now), batteryId);
        }
        return 1;
    }

    public void insertBinding(long id, long batteryId, long userId, long orderId, String evidence,
                              LocalDateTime now, long tenantId) {
        jdbc.update("INSERT INTO swap_battery_binding (id, battery_id, user_id, bind_state, bind_source, order_id, "
                        + "evidence, start_at, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'ACTIVE', 'ORDER', ?, ?, ?, ?, ?, 0, 0, ?)",
                id, batteryId, userId, orderId, evidence, Timestamp.valueOf(now), Timestamp.valueOf(now),
                Timestamp.valueOf(now), tenantId);
    }

    /** 换电完成时的仓位去向：归还仓收到旧电池变 IDLE_CHARGING，取走仓变空仓。 */
    public void settleSlot(long slotId, Long batteryId, String slotState, String chargeState, LocalDateTime now) {
        jdbc.update("UPDATE swap_slot SET battery_id = ?, slot_state = ?, charge_state = ?, reserved_order_id = NULL, "
                        + "last_detected_at = ?, update_time = ?, version = version + 1 WHERE id = ?",
                batteryId, slotState, chargeState, Timestamp.valueOf(now), Timestamp.valueOf(now), slotId);
    }

    public int releaseReservationsOfSlot(long slotId, long orderId, LocalDateTime now) {
        return jdbc.update("UPDATE swap_slot_reservation SET resv_state = 'RELEASED', released_at = ?, "
                + "release_reason = 'SWAP_DONE', update_time = ? WHERE slot_id = ? AND order_id = ? AND resv_state = 'ACTIVE'",
                Timestamp.valueOf(now), Timestamp.valueOf(now), slotId, orderId);
    }

    public void bindSlotsToOrder(long orderId, Long returnBatteryId, Long offerBatteryId) {
        jdbc.update("UPDATE swap_order SET return_battery_id = ?, offer_battery_id = ?, update_time = CURRENT_TIMESTAMP "
                + "WHERE id = ?", returnBatteryId, offerBatteryId, orderId);
    }

    /**
     * 把分配结果回写订单头。
     *
     * 为什么单独一个方法而不是合在建单 INSERT 里：建单时还没完成分配（先有 CREATED 行才能靠
     * active_user 唯一索引挡住同人二单），分配在它之后。但两个仓号必须落在订单头上——
     * 事件归属靠的就是它；只写步骤表与预占表会让订单头与步骤不一致，
     * 表现为“事件到了却被当成无关事件”（本项目的实际踩坑经）。
     */
    public void assignSlots(long orderId, Integer returnSlotNo, Integer offerSlotNo, Long offerBatteryId) {
        jdbc.update("UPDATE swap_order SET return_slot_no = ?, offer_slot_no = ?, offer_battery_id = ?, "
                + "update_time = CURRENT_TIMESTAMP WHERE id = ?", returnSlotNo, offerSlotNo, offerBatteryId, orderId);
    }

    /** 扣减：同幂等键 (order_id,'DEDUCT') 写流水；流水失败时账户不动。 */
    public boolean deductRight(long accountId, long memberId, long orderId, LocalDateTime now, String traceId,
                              long tenantId) {
        int updated = jdbc.update("UPDATE swap_right_account SET times_used = times_used + 1, "
                        + "times_occupied = CASE WHEN times_occupied > 0 THEN times_occupied - 1 ELSE 0 END, "
                        + "update_time = CURRENT_TIMESTAMP, version = version + 1 "
                        + "WHERE id = ? AND times_occupied > 0", accountId);
        if (updated != 1) {
            return false;
        }
        insertRightTransaction(IdWorkerLike.next(), memberId, accountId, orderId, "DEDUCT", 1, null, now, "SWAP_TAKEN",
                traceId, tenantId);
        return true;
    }

    /** 账实差异落点（柜侧陈述与云端事实不一致）。dedup_key 保证同一差异只记一次。 */
    public boolean insertDiscrepancy(long id, String dedupKey, String kind, Long orderId, Long cabinetId,
                                      Long batteryId, Long userId, String expectedJson, String actualJson,
                                      String reason, long tenantId) {
        try {
            jdbc.update("INSERT INTO swap_discrepancy (id, dedup_key, kind, order_id, cabinet_id, battery_id, user_id, "
                            + "expected_json, actual_json, auto_resolvable, handle_state, remark, create_time, "
                            + "update_time, version, del_flag, tenant_id) "
                            + "VALUES (?,?,?,?,?,?,?, ?,?, 0, 'OPEN', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, ?)",
                    id, dedupKey, kind, orderId, cabinetId, batteryId, userId, expectedJson, actualJson, reason, tenantId);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    /** 单号：SW + 日期 + 序列位。可读且能一眼看出日期。 */
    public static String newOrderNo(LocalDateTime now) {
        return String.format("SW%s%06d", now.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")),
                java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
