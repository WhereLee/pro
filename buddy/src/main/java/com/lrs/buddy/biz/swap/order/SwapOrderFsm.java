package com.lrs.buddy.biz.swap.order;

import com.lrs.buddy.framework.statemachine.StateMachine;
import java.util.EnumSet;
import java.util.Set;

/**
 * 订单状态机迁移表（swap-order-fsm §5 全表的代码化）。
 *
 * 三条实现立场：
 * 1 **未列出的 `(state,event)` 组合一律非法**，由 {@link StateMachine} 抛
 *    {@code IllegalStateTransitionException}。静默忽略等于"这个事件没人处理但没人知道"，
 *    是错单最典型的来源。
 * 2 终态不登记**自动**出边：一旦有出边就等于 COMPLETED 还能被改回去，
 *    而"改回已完成"在资金与资产上都会造成不可解释的账。
 *    例外只有一处且必须明写：FAILED_MANUAL 的 ADMIN_RESOLVE_*（§5.6 第 38 条）——
 *    它是"人工落终"而不是"自动回退"，需要 swap:order:intervene 权限与双人复核。
 *    测试按"除这两条外终态无出边"断言。
 * 3 §5.6 的"任意非终态"在这里**展开成显式多条登记**而不是通配。
 *    通配写法（if in-flight then ...）会让迁移表无法被穷举测试，
 *    也让人无法逐条回答"这个状态收到这个事件会发生什么"。
 *
 * 与 DDL 的对应：{@link OrderState#IN_FLIGHT} 必须与 V9 的 {@code active_user} 生成列同集合，
 * 由 SwapDdlContractTest 双向断言。
 */
public final class SwapOrderFsm {

    private static final StateMachine<OrderState, OrderEvent> MACHINE = build();

    private SwapOrderFsm() {
    }

    public static StateMachine<OrderState, OrderEvent> machine() {
        return MACHINE;
    }

    public static OrderEvent normalize(String name) {
        return OrderEvent.valueOf(name.toUpperCase(java.util.Locale.ROOT));
    }

    public static OrderState normalizeState(String name) {
        return OrderState.valueOf(name.toUpperCase(java.util.Locale.ROOT));
    }

    /** 主线 13 条 + 前置拒绝 3 条 + 超时与不可断定 10 条 + 取电异常 5 条 + 结算异常 4 条 + 安全与人工干预展开。 */
    private static StateMachine<OrderState, OrderEvent> build() {
        StateMachine<OrderState, OrderEvent> sm = new StateMachine<>("swap-order");

        // ---- §5.1 正常主线 ----
        sm.allow(OrderState.CREATED, OrderEvent.GUARD_PASS, OrderState.AUTHORIZED);
        sm.allow(OrderState.AUTHORIZED, OrderEvent.DISPATCH_S1, OrderState.RETURNING);
        // 门开与电池识别只是"步骤内的事实"，订单态仍停在 RETURNING：
        // 订单是粗粒度口径，若每个物理事件都推订单态，§4.1 的 15 个状态就不够用了
        sm.allow(OrderState.RETURNING, OrderEvent.EVT_DOOR_OPEN_RETURN, OrderState.RETURNING);
        sm.allow(OrderState.RETURNING, OrderEvent.EVT_BATTERY_DETECTED_RETURN, OrderState.RETURNING);
        sm.allow(OrderState.RETURNING, OrderEvent.EVT_DOOR_CLOSE_RETURN, OrderState.RETURNED);
        sm.allow(OrderState.RETURNED, OrderEvent.ENTER_S3, OrderState.VERIFYING);
        sm.allow(OrderState.VERIFYING, OrderEvent.VERIFY_OK, OrderState.OFFERING);
        sm.allow(OrderState.OFFERING, OrderEvent.DISPATCH_S4, OrderState.OFFERING);
        sm.allow(OrderState.OFFERING, OrderEvent.EVT_DOOR_OPEN_OFFER, OrderState.OFFERING);
        // 电池已被取走但门未关：订单不能被门的状态卡住，见 §5.4 第 29 条的 TAKEN_BUT_DOOR_OPEN
        sm.allow(OrderState.OFFERING, OrderEvent.EVT_BATTERY_TAKEN_OFFER, OrderState.OFFERING);
        sm.allow(OrderState.OFFERING, OrderEvent.EVT_DOOR_CLOSE_OFFER, OrderState.TAKEN);
        sm.allow(OrderState.TAKEN, OrderEvent.ENTER_S6, OrderState.SETTLING);
        sm.allow(OrderState.SETTLING, OrderEvent.SETTLE_DONE, OrderState.COMPLETED);

        // ---- §5.2 前置拒绝（零物理动作 → REJECTED）----
        sm.allow(OrderState.CREATED, OrderEvent.GUARD_FAIL, OrderState.REJECTED);
        sm.allow(OrderState.AUTHORIZED, OrderEvent.CMD_NACK_NOT_EXECUTED, OrderState.REJECTED);
        sm.allow(OrderState.RETURNING, OrderEvent.CMD_EXPIRED, OrderState.REJECTED);

        // ---- §5.3 超时与不可断定 ----
        sm.allow(OrderState.RETURNING, OrderEvent.DEADLINE_S1_DOOR_OPEN, OrderState.RETURNING);
        sm.allow(OrderState.RETURNING, OrderEvent.DEADLINE_S1_DOOR_CLOSED, OrderState.RETURNING);
        sm.allow(OrderState.RETURNING, OrderEvent.DEADLINE_S1_UNKNOWABLE, OrderState.UNCONFIRMED);
        sm.allow(OrderState.RETURNING, OrderEvent.DEADLINE_NO_INSERT, OrderState.SUSPENDED);
        sm.allow(OrderState.RETURNING, OrderEvent.DEADLINE_DOOR_NOT_CLOSED, OrderState.SUSPENDED);
        sm.allow(OrderState.SUSPENDED, OrderEvent.USER_DECLARE_CLOSED_VERIFIED, OrderState.RETURNED);
        // 声明了但反查仍没关：状态不变（继续等），副作用是锁仓 + 开工单。
        // 登记成自迁移而不是"不迁移"，是为了让这条路径也进事件流、可被审计。
        sm.allow(OrderState.SUSPENDED, OrderEvent.USER_DECLARE_CLOSED_UNVERIFIED, OrderState.SUSPENDED);
        sm.allow(OrderState.SUSPENDED, OrderEvent.DEADLINE_SUSPENDED, OrderState.ABORTING);
        sm.allow(OrderState.UNCONFIRMED, OrderEvent.QUERY_CONSISTENT_RETURNED, OrderState.RETURNED);
        sm.allow(OrderState.UNCONFIRMED, OrderEvent.QUERY_CONSISTENT_TAKEN, OrderState.TAKEN);
        sm.allow(OrderState.UNCONFIRMED, OrderEvent.DEADLINE_UNCONFIRMED, OrderState.FAILED_MANUAL);

        // ---- §5.4 取电阶段异常 ----
        sm.allow(OrderState.OFFERING, OrderEvent.DEADLINE_S4_NOT_OPEN, OrderState.OFFERING);
        sm.allow(OrderState.OFFERING, OrderEvent.DEADLINE_TAKE_NOT_TAKEN, OrderState.ABORTING);
        sm.allow(OrderState.OFFERING, OrderEvent.TAKEN_BUT_DOOR_OPEN, OrderState.TAKEN);
        sm.allow(OrderState.OFFERING, OrderEvent.SLOT_EMPTY_WITHOUT_TAKE, OrderState.UNCONFIRMED);
        sm.allow(OrderState.ABORTING, OrderEvent.ABORT_DONE, OrderState.ABORTED);

        // ---- §5.5 拒收与结算异常 ----
        sm.allow(OrderState.VERIFYING, OrderEvent.VERIFY_FAIL_REJECT, OrderState.ABORTING);
        sm.allow(OrderState.OFFERING, OrderEvent.LATE_VERIFY_FAIL, OrderState.SETTLING);
        sm.allow(OrderState.SETTLING, OrderEvent.SETTLE_FAIL, OrderState.SETTLING);
        sm.allow(OrderState.SETTLING, OrderEvent.SETTLE_RETRY_EXHAUSTED, OrderState.SETTLING);

        // ---- §5.6 安全联动与管理员干预：把"任意非终态"展开成显式登记 ----
        for (OrderState state : EnumSet.allOf(OrderState.class)) {
            if (state.isTerminal()) {
                continue;
            }
            sm.allow(state, OrderEvent.ALARM_SAFETY_LOCK, OrderState.ABORTING);
            sm.allow(state, OrderEvent.ADMIN_ABORT, OrderState.FAILED_MANUAL);
        }
        sm.allow(OrderState.FAILED_MANUAL, OrderEvent.ADMIN_RESOLVE_COMPLETED, OrderState.COMPLETED);
        sm.allow(OrderState.FAILED_MANUAL, OrderEvent.ADMIN_RESOLVE_ABORTED, OrderState.ABORTED);
        return sm;
    }

    /** §5.6 第 39 条 restart_recover 不是事件：进程重启后由兜底扫描按最后一条事实重建现场再投递常规事件。 */
    public static Set<OrderEvent> factEvents() {
        return Set.of(OrderEvent.EVT_DOOR_OPEN_RETURN, OrderEvent.EVT_BATTERY_DETECTED_RETURN,
                OrderEvent.EVT_DOOR_CLOSE_RETURN, OrderEvent.EVT_DOOR_OPEN_OFFER,
                OrderEvent.EVT_BATTERY_TAKEN_OFFER, OrderEvent.EVT_DOOR_CLOSE_OFFER);
    }
}
