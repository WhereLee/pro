package com.lrs.buddy.biz.swap.order;

import java.util.Set;

/**
 * 步骤态（§4.2 末段，与 V9 的 ck_step_state 逐字一致）。
 *
 * 两个容易被省掉、但省掉就无法表达真实世界的态：
 * <ul>
 *   <li>{@code CONFIRM_PENDING}：ACK 已到但物理事件缺失、反查未穷尽。
 *       订单层的 {@code UNCONFIRMED} 就是由它投影而来。没有这个态，
 *       "设备说做了但没看见它做"就只能被压成成功或失败二选一。</li>
 *   <li>{@code SKIPPED}：该步骤本单不再执行（换仓位后旧步骤作废、或站点策略免除反查）。
 *       必须计入事件流而不是删行/静默消失，否则重放事件流重建出的现场与真实现场不一致。</li>
 * </ul>
 */
public enum StepState {

    PENDING,
    DISPATCHED,
    OPEN_CONFIRMED,
    PHYSICS_DONE,
    VERIFIED,
    CONFIRM_PENDING,
    FAILED,
    SKIPPED;

    /**
     * 真正封口的终态。注意 **PHYSICS_DONE 不是终态**：
     * §4.2 的主线是 … → PHYSICS_DONE → VERIFIED，S3 的核验就发生在物理动作完成之后。
     * 把它当终态会直接让 VERIFY_RETURN 步骤无法进入 VERIFIED（穷举测试抓到的就是这一处）。
     */
    public static final Set<StepState> TERMINAL = Set.of(VERIFIED, FAILED, SKIPPED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * “本步骤已不再需要重发”的判定：包含 PHYSICS_DONE。
     * 与 isTerminal() 是两个不同问题——前者问要不要重发，后者问还能不能再被推动。
     */
    public boolean isDone() {
        return this == PHYSICS_DONE || this == VERIFIED || this == SKIPPED;
    }
}
