package com.lrs.buddy.framework.statemachine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机骨架的三条硬行为：未登记组合必抛、守卫不过与非法迁移要可区分、重复登记要拦。
 *
 * 这三条决定了 M2 的状态机是否可诊断 —— 若 fire 对未知事件静默返回原状态，
 * "这个事件没人处理"就会变成一条永远不报错的错单。
 */
class StateMachineTest {

    private enum State { IDLE, RUNNING, DONE }

    private enum Event { START, FINISH, FORGET }

    private static StateMachine<State, Event> machine() {
        return new StateMachine<State, Event>("test")
                .allow(State.IDLE, Event.START, State.RUNNING)
                .allow(State.RUNNING, Event.FINISH, State.DONE);
    }

    @Test
    @DisplayName("登记的迁移正常推进")
    void firesRegisteredTransitions() {
        StateMachine<State, Event> sm = machine();
        assertThat(sm.fire(State.IDLE, Event.START, null)).isEqualTo(State.RUNNING);
        assertThat(sm.fire(State.RUNNING, Event.FINISH, null)).isEqualTo(State.DONE);
        assertThat(sm.transitionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("未登记的组合抛非法迁移异常，不静默返回原状态")
    void unregisteredCombinationThrows() {
        StateMachine<State, Event> sm = machine();
        assertThatThrownBy(() -> sm.fire(State.IDLE, Event.FINISH, null))
                .as("静默返回原状态会让事件石沉大海")
                .isInstanceOf(StateMachine.IllegalStateTransitionException.class);
        assertThat(sm.canFire(State.DONE, Event.START)).isFalse();
    }

    @Test
    @DisplayName("守卫不过与非法迁移是两类错误，必须可区分")
    void guardFailureIsDistinctFromIllegalTransition() {
        StateMachine<State, Event> sm = new StateMachine<State, Event>("guarded")
                .allow(State.IDLE, Event.START, (from, event, ctx) -> Boolean.TRUE.equals(ctx),
                        (from, event, ctx) -> State.RUNNING);
        assertThat(sm.fire(State.IDLE, Event.START, Boolean.TRUE)).isEqualTo(State.RUNNING);
        assertThatThrownBy(() -> sm.fire(State.IDLE, Event.START, Boolean.FALSE))
                .isInstanceOf(StateMachine.ConditionNotMetException.class);
    }

    @Test
    @DisplayName("重复登记同一组合直接失败（防止后写的静默覆盖先写的）")
    void duplicateRegistrationIsRejected() {
        StateMachine<State, Event> sm = machine();
        assertThatThrownBy(() -> sm.allow(State.IDLE, Event.START, State.DONE))
                .isInstanceOf(IllegalStateException.class);
    }
}
