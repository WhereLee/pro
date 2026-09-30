package com.lrs.buddy.biz.swap.order;

import com.lrs.buddy.framework.statemachine.StateMachine;

/**
 * 步骤状态机（§4.2 的步骤态迁移）。
 *
 * 与订单状态机的分工：步骤只被"指令 ACK/物理事件/反查结果"推进，
 * 订单只被"步骤完成组合"推导出来的事实推进。两层各自穷举迁移表，
 * 混用（用订单态直接表示"门开了一半"）会让状态数失控并且无法重放。
 */
public final class SwapStepFsm {

    /** 步骤事件。命名即"发生的事实"，不是"收到的报文类型"——ACK 与"门开"是两件事。 */
    public enum Event {
        /** 指令已下发（先落库再下发，见 protocol §7.4） */
        DISPATCHED,
        /** 同一指令重发（只允许在无副作用的前提下，如 S1 反查门未开） */
        RE_DISPATCHED,
        /** 收到 door_open */
        EVT_DOOR_OPEN,
        /**
         * 无指令步骤（S2/S5）收到第一个事实。
         * 不能复用 EVT_DOOR_OPEN：那会让"等待投入"步骤的第一个事实被当成"门开了"，
         * 语义错误而且事后无法从事件流里区分两者。
         */
        EVT_FACT_ARRIVED,
        /** 收到本步骤期望的全部物理事件（S2/S5 是"两个事件都到"） */
        EVT_PHYSICS_DONE,
        /** ACK 已到但物理事件缺失（不可断定，不是失败） */
        ACK_WITHOUT_EVENT,
        /** 迟到的物理事件把不可断定纠正回来 */
        LATE_EVENT,
        /** 设备明确未执行（副作用未发生） */
        NACK_NOT_EXECUTED,
        /** 反查穷尽仍无法断定，本步骤判失败 */
        QUERY_EXHAUSTED_FAIL,
        /** 核验通过（S3） */
        VERIFIED,
        /** 本步骤作废（换仓位、或订单进入中止/拒收） */
        SUPERSEDED_SKIP
    }

    private static final StateMachine<StepState, Event> MACHINE = build();

    private SwapStepFsm() {
    }

    public static StateMachine<StepState, Event> machine() {
        return MACHINE;
    }

    private static StateMachine<StepState, Event> build() {
        StateMachine<StepState, Event> sm = new StateMachine<>("swap-step");
        sm.allow(StepState.PENDING, Event.DISPATCHED, StepState.DISPATCHED);
        sm.allow(StepState.PENDING, Event.EVT_FACT_ARRIVED, StepState.OPEN_CONFIRMED);
        // S6（SETTLE）没有指令也没有设备事件，它的"完成"就是云内事务提交。
        // 允许 PENDING 直接到 PHYSICS_DONE，否则结算要凭空多一次假下发才能合法推进。
        sm.allow(StepState.PENDING, Event.EVT_PHYSICS_DONE, StepState.PHYSICS_DONE);
        sm.allow(StepState.PENDING, Event.SUPERSEDED_SKIP, StepState.SKIPPED);
        sm.allow(StepState.DISPATCHED, Event.RE_DISPATCHED, StepState.DISPATCHED);
        sm.allow(StepState.DISPATCHED, Event.EVT_DOOR_OPEN, StepState.OPEN_CONFIRMED);
        sm.allow(StepState.DISPATCHED, Event.EVT_PHYSICS_DONE, StepState.PHYSICS_DONE);
        sm.allow(StepState.DISPATCHED, Event.ACK_WITHOUT_EVENT, StepState.CONFIRM_PENDING);
        sm.allow(StepState.DISPATCHED, Event.NACK_NOT_EXECUTED, StepState.FAILED);
        sm.allow(StepState.DISPATCHED, Event.SUPERSEDED_SKIP, StepState.SKIPPED);
        sm.allow(StepState.OPEN_CONFIRMED, Event.EVT_PHYSICS_DONE, StepState.PHYSICS_DONE);
        sm.allow(StepState.OPEN_CONFIRMED, Event.ACK_WITHOUT_EVENT, StepState.CONFIRM_PENDING);
        sm.allow(StepState.OPEN_CONFIRMED, Event.NACK_NOT_EXECUTED, StepState.FAILED);
        sm.allow(StepState.PHYSICS_DONE, Event.VERIFIED, StepState.VERIFIED);
        // 核验型步骤（S3）没有物理动作，PHYSICS_DONE 对它是空转：允许直接从 DISPATCHED 落 VERIFIED。
        // 不这么定就会被迫“先假地推一次 PHYSICS_DONE 再推 VERIFIED”，事件流里多出一条无意义记录。
        sm.allow(StepState.DISPATCHED, Event.VERIFIED, StepState.VERIFIED);
        // CONFIRM_PENDING 的两个出口正是"不可断定"的两条收敛路径：来事实了就纠正，穷尽了就判失败
        sm.allow(StepState.CONFIRM_PENDING, Event.LATE_EVENT, StepState.PHYSICS_DONE);
        sm.allow(StepState.CONFIRM_PENDING, Event.EVT_PHYSICS_DONE, StepState.PHYSICS_DONE);
        sm.allow(StepState.CONFIRM_PENDING, Event.QUERY_EXHAUSTED_FAIL, StepState.FAILED);
        sm.allow(StepState.CONFIRM_PENDING, Event.SUPERSEDED_SKIP, StepState.SKIPPED);
        return sm;
    }
}
