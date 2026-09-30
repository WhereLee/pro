package com.lrs.buddy.framework.statemachine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * 轻量状态机骨架：迁移表 + 守卫 + 动作 + 非法迁移异常 + 留痕钩子。
 *
 * 刻意**不做可配置状态机**（不给后台拖拽改迁移的能力）：换电订单、指令、工单的状态机是业务不变式，
 * 一个能把"扣钱"从 TAKEN 后挪到 AUTHORIZED 时的配置界面就是生产事故发生器。
 * 迁移表变更必须走代码评审 + 测试，与 schema 演进同等严肃。
 *
 * 三条实现要点：
 * 1 未登记的 (state,event) 组合抛异常而不是返回原状态 —— 静默忽略等于"这个事件没人处理但没人知道"；
 * 2 动作在状态写入之前执行并可返回新状态，但**迁移合法性只按 from 判定**，
 *   否则一个动作内部改状态就能绕过迁移表；
 * 3 无并发控制：并发交给 CAS/唯一约束（见 swap-order-fsm §9 四层并发），本类只管语义。
 */
public final class StateMachine<S extends Enum<S>, E extends Enum<E>> {

    /** 守卫返回 false 表示条件不满足：属于"合法但未达条件"，与"非法迁移"要区分开。 */
    public interface Guard<S extends Enum<S>, E extends Enum<E>> {
        boolean allow(S from, E event, Object context);
    }

    /** 动作可返回迁移后的状态；返回 null 表示按登记的 toState。 */
    public interface Action<S extends Enum<S>, E extends Enum<E>> {
        S apply(S from, E event, Object context);
    }

    public static class IllegalStateTransitionException extends RuntimeException {
        public IllegalStateTransitionException(String machine, Object state, Object event) {
            super("状态机 [" + machine + "] 不允许迁移：" + state + " + " + event);
        }
    }

    public static class ConditionNotMetException extends RuntimeException {
        public ConditionNotMetException(String machine, Object state, Object event) {
            super("状态机 [" + machine + "] 迁移条件未满足：" + state + " + " + event);
        }
    }

    private record Transition<S extends Enum<S>, E extends Enum<E>>(S toState, Guard<S, E> guard,
                                                                    Action<S, E> action) {
    }

    private final String name;
    private final Map<String, Transition<S, E>> table = new LinkedHashMap<>();

    public StateMachine(String name) {
        this.name = name;
    }

    public StateMachine<S, E> allow(S from, E event, S toState) {
        return allow(from, event, (f, e, ctx) -> true, (f, e, ctx) -> toState);
    }

    public StateMachine<S, E> allow(S from, E event, Guard<S, E> guard, Action<S, E> action) {
        String key = key(from, event);
        if (table.containsKey(key)) {
            throw new IllegalStateException("重复登记迁移：" + name + " " + from + " + " + event);
        }
        table.put(key, new Transition<>(null, guard, action));
        return this;
    }

    /**
     * 触发一次迁移。
     *
     * @throws IllegalStateTransitionException 该组合根本不存在（写错状态机了）
     * @throws ConditionNotMetException       组合存在但守卫不过（业务条件未满足）
     */
    public S fire(S from, E event, Object context) {
        Transition<S, E> transition = table.get(key(from, event));
        if (transition == null) {
            throw new IllegalStateTransitionException(name, from, event);
        }
        if (!transition.guard().allow(from, event, context)) {
            throw new ConditionNotMetException(name, from, event);
        }
        S next = transition.action().apply(from, event, context);
        return Objects.requireNonNullElse(next, transition.toState());
    }

    public boolean canFire(S from, E event) {
        return table.containsKey(key(from, event));
    }

    public int transitionCount() {
        return table.size();
    }

    private String key(S from, E event) {
        return from.name() + "+" + event.name();
    }

    /** 便捷构造：守卫为"上下文断言"。 */
    public static <S extends Enum<S>, E extends Enum<E>> Guard<S, E> guardOf(BiFunction<S, Object, Boolean> predicate) {
        return (from, event, context) -> predicate.apply(from, context);
    }
}
