package com.lrs.buddy.biz.swap.order;

import com.lrs.buddy.framework.statemachine.StateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 迁移表穷举测试（M2 验证门的核心一条：未列出的 `(state,event)` 组合必须抛异常）。
 *
 * 这里刻意用**穷举**而不是"挑几条正例跑一遍"：
 * 正例只能证明登记过的边是对的，证明不了没登记的那些被正确拒绝。
 * 而线上出事的地方恰恰是"某个状态下收到一个不该收到的事件"——
 * 如果那时 FSM 静默返回原状态，错单就会一路无人拦截地走下去。
 */
class SwapOrderFsmTest {

    private static final StateMachine<OrderState, OrderEvent> FSM = SwapOrderFsm.machine();

    @Test
    @DisplayName("穷举 15×41 组合：登记的必须可迁移，未登记的必须抛非法迁移异常")
    void everyCombinationIsEitherRegisteredOrRejected() {
        int legal = 0;
        int illegal = 0;
        List<String> wrongTargets = new ArrayList<>();
        for (OrderState state : OrderState.values()) {
            for (OrderEvent event : OrderEvent.values()) {
                if (FSM.canFire(state, event)) {
                    legal++;
                    OrderState to = FSM.fire(state, event, null);
                    if (to == null) {
                        wrongTargets.add(state + "+" + event + " -> null");
                    }
                } else {
                    illegal++;
                    final OrderState from = state;
                    assertThatThrownBy(() -> FSM.fire(from, event, null))
                            .as("未登记组合必须抛异常，不能静默返回原状态")
                            .isInstanceOf(StateMachine.IllegalStateTransitionException.class);
                }
            }
        }
        assertThat(wrongTargets).isEmpty();
        assertThat(legal).isEqualTo(FSM.transitionCount());
        assertThat(legal).as("登记数应远多于 39：§5.6 的『任意非终态』在代码里展开成显式多条")
                .isGreaterThanOrEqualTo(39);
        assertThat(illegal).as("绝大多数组合必须是非法的，穷举才有意义").isGreaterThan(legal);
    }

    @Test
    @DisplayName("终态没有自动出边；唯一例外是 FAILED_MANUAL 的人工核资落终")
    void terminalStatesHaveNoAutomaticOutgoing() {
        for (OrderState state : OrderState.values()) {
            if (!state.isTerminal()) {
                continue;
            }
            for (OrderEvent event : OrderEvent.values()) {
                boolean allowedHumanResolve = state == OrderState.FAILED_MANUAL
                        && (event == OrderEvent.ADMIN_RESOLVE_COMPLETED || event == OrderEvent.ADMIN_RESOLVE_ABORTED);
                if (allowedHumanResolve) {
                    assertThat(FSM.canFire(state, event)).as("人工核资必须能落终").isTrue();
                } else {
                    assertThat(FSM.canFire(state, event))
                            .as(state + " 是终态，" + event + " 不该有出边").isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("正常主线 13 条逐步走通，目标态与 §5.1 完全一致")
    void mainLineWalksExactlyAsDocumented() {
        OrderState state = OrderState.CREATED;
        state = FSM.fire(state, OrderEvent.GUARD_PASS, null);
        assertThat(state).isEqualTo(OrderState.AUTHORIZED);
        state = FSM.fire(state, OrderEvent.DISPATCH_S1, null);
        assertThat(state).isEqualTo(OrderState.RETURNING);
        state = FSM.fire(state, OrderEvent.EVT_DOOR_OPEN_RETURN, null);
        assertThat(state).as("门开是步骤内事实，订单仍停 RETURNING").isEqualTo(OrderState.RETURNING);
        state = FSM.fire(state, OrderEvent.EVT_BATTERY_DETECTED_RETURN, null);
        assertThat(state).isEqualTo(OrderState.RETURNING);
        state = FSM.fire(state, OrderEvent.EVT_DOOR_CLOSE_RETURN, null);
        assertThat(state).isEqualTo(OrderState.RETURNED);
        state = FSM.fire(state, OrderEvent.ENTER_S3, null);
        assertThat(state).isEqualTo(OrderState.VERIFYING);
        state = FSM.fire(state, OrderEvent.VERIFY_OK, null);
        assertThat(state).isEqualTo(OrderState.OFFERING);
        state = FSM.fire(state, OrderEvent.DISPATCH_S4, null);
        assertThat(state).isEqualTo(OrderState.OFFERING);
        state = FSM.fire(state, OrderEvent.EVT_DOOR_OPEN_OFFER, null);
        state = FSM.fire(state, OrderEvent.EVT_BATTERY_TAKEN_OFFER, null);
        state = FSM.fire(state, OrderEvent.EVT_DOOR_CLOSE_OFFER, null);
        assertThat(state).isEqualTo(OrderState.TAKEN);
        state = FSM.fire(state, OrderEvent.ENTER_S6, null);
        assertThat(state).isEqualTo(OrderState.SETTLING);
        state = FSM.fire(state, OrderEvent.SETTLE_DONE, null);
        assertThat(state).isEqualTo(OrderState.COMPLETED);
    }

    @Test
    @DisplayName("扣减只允许发生在 TAKEN 之后，RETURNING 阶段绝不放过")
    void deductionTimingIsGuardedByState() {
        assertThat(OrderState.CREATED.allowsDeduction()).isFalse();
        assertThat(OrderState.AUTHORIZED.allowsDeduction()).isFalse();
        assertThat(OrderState.RETURNING.allowsDeduction()).isFalse();
        assertThat(OrderState.VERIFYING.allowsDeduction()).isFalse();
        assertThat(OrderState.OFFERING.allowsDeduction())
                .as("还在取电阶段就扣款，用户可能一分钱没拿到电池").isFalse();
        assertThat(OrderState.TAKEN.allowsDeduction()).isTrue();
        assertThat(OrderState.SETTLING.allowsDeduction()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderState.class, names = {"CREATED", "AUTHORIZED", "RETURNING", "RETURNED",
            "VERIFYING", "OFFERING", "TAKEN", "SETTLING", "SUSPENDED", "UNCONFIRMED", "ABORTING"})
    @DisplayName("安全联动与管理员中止在任意在途状态都成立（§5.6 展开的等价性检查）")
    void alarmAndAdminAbortAvailableFromAnyInfightState(OrderState state) {
        assertThat(FSM.fire(state, OrderEvent.ALARM_SAFETY_LOCK, null)).isEqualTo(OrderState.ABORTING);
        assertThat(FSM.fire(state, OrderEvent.ADMIN_ABORT, null)).isEqualTo(OrderState.FAILED_MANUAL);
    }

    @Test
    @DisplayName("在途集合与终态集合互斥且并起来是全集（与 V9 active_user 生成列同集合）")
    void stateSetsAreConsistent() {
        assertThat(OrderState.IN_FLIGHT).hasSize(11);
        assertThat(OrderState.TERMINAL).hasSize(4);
        assertThat(OrderState.IN_FLIGHT).doesNotContainAnyElementsOf(OrderState.TERMINAL);
        assertThat(OrderState.IN_FLIGHT.size() + OrderState.TERMINAL.size())
                .as("没有状态既不在途也不终态——那样它无法被兜底扫描归类")
                .isEqualTo(OrderState.values().length);
    }

    @Test
    @DisplayName("不可断定不是死路：既可由迟到事实纠正，也可超时落人工")
    void unconfirmedHasBothExits() {
        assertThat(FSM.fire(OrderState.UNCONFIRMED, OrderEvent.QUERY_CONSISTENT_TAKEN, null))
                .isEqualTo(OrderState.TAKEN);
        assertThat(FSM.fire(OrderState.UNCONFIRMED, OrderEvent.DEADLINE_UNCONFIRMED, null))
                .as("自动裁决不可达必须落到人工，而不是继续自动跑").isEqualTo(OrderState.FAILED_MANUAL);
    }

    @Test
    @DisplayName("拒收与中止的分岔：未取电走 ABORTING，已取电允许完成")
    void rejectAndAbortBranches() {
        assertThat(FSM.fire(OrderState.VERIFYING, OrderEvent.VERIFY_FAIL_REJECT, null))
                .isEqualTo(OrderState.ABORTING);
        assertThat(FSM.fire(OrderState.OFFERING, OrderEvent.LATE_VERIFY_FAIL, null))
                .as("用户手上已有电池时允许完成扣减，不能强行中止成电池在人手上但订单已中止")
                .isEqualTo(OrderState.SETTLING);
    }

    @Test
    @DisplayName("步骤状态机穷举：登记的通过、未登记的抛异常")
    void stepFsmIsExhaustive() {
        StateMachine<StepState, SwapStepFsm.Event> steps = SwapStepFsm.machine();
        int legal = 0;
        for (StepState state : StepState.values()) {
            for (SwapStepFsm.Event event : SwapStepFsm.Event.values()) {
                if (steps.canFire(state, event)) {
                    legal++;
                    assertThat(steps.fire(state, event, null)).isNotNull();
                } else {
                    assertThatThrownBy(() -> steps.fire(state, event, null))
                            .isInstanceOf(StateMachine.IllegalStateTransitionException.class);
                }
            }
        }
        assertThat(legal).isEqualTo(steps.transitionCount());
    }

    @Test
    @DisplayName("CONFIRM_PENDING 的两条收敛路径：迟到事实纠正、反查穷尽判失败")
    void confirmPendingConvergesBothWays() {
        StateMachine<StepState, SwapStepFsm.Event> steps = SwapStepFsm.machine();
        assertThat(steps.fire(StepState.CONFIRM_PENDING, SwapStepFsm.Event.LATE_EVENT, null))
                .isEqualTo(StepState.PHYSICS_DONE);
        assertThat(steps.fire(StepState.CONFIRM_PENDING, SwapStepFsm.Event.QUERY_EXHAUSTED_FAIL, null))
                .isEqualTo(StepState.FAILED);
        assertThat(StepState.CONFIRM_PENDING.isTerminal())
                .as("不可断定不是终态：还有两条出口，否则就永久悬挂").isFalse();
    }

    @Test
    @DisplayName("VERIFIED/FAILED/SKIPPED 是步骤终态，不再被任何事件推动；PHYSICS_DONE 不是")
    void stepTerminalStatesAreClosed() {
        StateMachine<StepState, SwapStepFsm.Event> steps = SwapStepFsm.machine();
        for (StepState state : StepState.TERMINAL) {
            for (SwapStepFsm.Event event : SwapStepFsm.Event.values()) {
                assertThat(steps.canFire(state, event))
                        .as(state + " 是步骤终态").isFalse();
            }
        }
        assertThat(StepState.PHYSICS_DONE.isTerminal())
                .as("物理完成仍需要进核验（S3），它不算封口终态，但已不需要重发")
                .isFalse();
        assertThat(StepState.PHYSICS_DONE.isDone()).isTrue();
    }
}
