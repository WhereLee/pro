package com.lrs.buddy.framework.iot.command;

import java.util.Set;

/**
 * 指令生命周期状态（swap-protocol.md §4.2）。
 *
 * 关键设计：TIMEOUT 与 UNCONFIRMED **不是终态**。把它们当终态，
 * 就等于在代码里承认"超时就是没发生" —— 而超时抖掉的往往只是应答，
 * 门可能真的开了。非终态才会强制走反查与人工收敛这条正确路径。
 */
public enum CommandState {

    CREATED,
    DISPATCHED,
    ACKED,
    CONFIRMED,
    NACKED,
    TIMEOUT,
    EXPIRED,
    SUPERSEDED,
    UNCONFIRMED;

    /** 终态：不再有任何路径会改变它。 */
    public static final Set<CommandState> TERMINAL = Set.of(CONFIRMED, NACKED, EXPIRED, SUPERSEDED);

    /** 在途：占用"同一业务步骤至多一条在途指令"的唯一约束（生成列）。 */
    public static final Set<CommandState> IN_FLIGHT = Set.of(CREATED, DISPATCHED, ACKED, UNCONFIRMED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isInfight() {
        return IN_FLIGHT.contains(this);
    }

    /**
     * 合法迁移表。未列出的组合视为非法（抛异常），不允许静默改写状态。
     *
     * 为什么允许 TIMEOUT→ACKED：迟到应答到达时，事实是"设备回过了"，
     * 必须能纠正回 ACKED；反过来禁止 CONFIRMED→任何，终态不可回退。
     */
    public boolean canGoTo(CommandState target) {
        return switch (this) {
            case CREATED -> target == DISPATCHED || target == EXPIRED || target == SUPERSEDED;
            case DISPATCHED -> target == ACKED || target == NACKED || target == TIMEOUT
                    || target == EXPIRED || target == SUPERSEDED;
            case ACKED -> target == CONFIRMED || target == UNCONFIRMED || target == NACKED;
            case TIMEOUT -> target == ACKED || target == CONFIRMED || target == UNCONFIRMED
                    || target == EXPIRED || target == SUPERSEDED;
            case UNCONFIRMED -> target == ACKED || target == CONFIRMED || target == NACKED;
            case CONFIRMED, NACKED, EXPIRED, SUPERSEDED -> false;
        };
    }
}
