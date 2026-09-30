package com.lrs.buddy.biz.swap.order;

import java.util.Set;

/**
 * 订单状态（swap-order-fsm §4.1，粗粒度、资金口径）。
 *
 * 三个终态的职责必须分开，这是本项目最容易糊掉的一条：
 * <ul>
 *   <li>{@code REJECTED}：从未发出任何物理动作即被拒 → 自动全额退、释放预占、不计次、不开工单（只计拒绝率）</li>
 *   <li>{@code ABORTED}：已发出物理动作后中止且补偿集已完成 → 不扣权益</li>
 *   <li>{@code FAILED_MANUAL}：自动裁决不可达 → <b>禁止自动退款</b>，人工核资后回写，强制工单 + 资产盘点</li>
 * </ul>
 * 把后两者合并成一个 FAILED，等于在代码里承认"失败的处理方式只有一种"——
 * 而真实差异是"有没有发生过物理动作"，直接决定资金与资产能不能自动闭环。
 */
public enum OrderState {

    CREATED,
    AUTHORIZED,
    RETURNING,
    RETURNED,
    VERIFYING,
    OFFERING,
    TAKEN,
    SETTLING,
    COMPLETED,
    SUSPENDED,
    UNCONFIRMED,
    ABORTING,
    REJECTED,
    ABORTED,
    FAILED_MANUAL;

    /** 终态：进入后不允许任何再迁移（I8 与人工核资的落点）。 */
    public static final Set<OrderState> TERMINAL = Set.of(COMPLETED, REJECTED, ABORTED, FAILED_MANUAL);

    /**
     * 在途集合：必须与 V9 生成列 {@code active_user} 的 CASE 分支**逐字一致**，
     * 由 SwapDdlContractTest 双向断言。多一个少一个都意味着
     * "B3 单用户在途至多 1 笔"这条业务约束与 DB 约束不再同义。
     */
    public static final Set<OrderState> IN_FLIGHT = Set.of(CREATED, AUTHORIZED, RETURNING, RETURNED,
            VERIFYING, OFFERING, TAKEN, SETTLING, SUSPENDED, UNCONFIRMED, ABORTING);

    /**
     * 该状态下允许自动扣减权益的时机集合只有一个点：TAKEN 之后。
     * 早于取电扣款会让"没拿到电池的用户已被计费"；晚于结算就无法保证 I3 同事务。
     */
    public boolean allowsDeduction() {
        return this == TAKEN || this == SETTLING || this == COMPLETED;
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isInfight() {
        return IN_FLIGHT.contains(this);
    }

    /** 状态最大停留秒数（§4.1"最大停留"列，兜底扫描按它算 deadline）。 */
    public int maxDwellSeconds() {
        return switch (this) {
            case CREATED -> 5;
            case AUTHORIZED -> 3;
            case RETURNING -> 150;
            case RETURNED -> 5;
            case VERIFYING -> 25;
            case OFFERING -> 180;
            case TAKEN -> 3;
            case SETTLING -> 30;
            case SUSPENDED -> 600;
            case UNCONFIRMED -> 900;
            case ABORTING -> 60;
            case COMPLETED, REJECTED, ABORTED, FAILED_MANUAL -> 0;
        };
    }
}
