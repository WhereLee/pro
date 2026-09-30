package com.lrs.buddy.biz.swap.compensation;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 补偿动作目录（M3 §6 / swap-order-fsm §7）。
 *
 * 这里不新造名字：**动作名与 DDL 的 {@code ck_comp_action} 逐字对齐**，
 * 并由 {@code SwapDdlContractTest} 钉住三方一致（文档 ↔ DDL CHECK ↔ 本枚举）。
 * 原因很实际：补偿台账是要能被对账与审计复算的，一旦代码里出现 DDL 不认的动作名，
 * 写入直接被 CHECK 拒；反过来 DDL 里有、代码没有，就会留下"能写但没人执行"的空动作。
 *
 * 两个分类信息决定了执行器怎么写：
 * <ul>
 *   <li>{@link #blocking()} —— 是否属于"落终态前必须完成"的补偿集。
 *       非阻塞项（如 CREATE_WORK_ORDER / ESCALATE_ALARM）失败只降级为告警，不卡 ABORTED；
 *       否则一条工单系统抖动就能让订单永远进不了终态。</li>
 *   <li>{@link #idempotentKeyPrefix()} —— 幂等键前缀。补偿必须可重试，
 *       而"可重试"的前提是同一动作有稳定身份，否则重试就是在重复退权益。</li>
 * </ul>
 */
public enum CompensationAction {

    /** 释放该单在仓位预占台账上的 ACTIVE 记录 */
    RELEASE_RESERVATION("SLOT", true),
    /** 锁仓（禁止再被分配；仓内有电池时禁止停用） */
    LOCK_SLOT("SLOT", false),
    /** 锁柜（整柜退出分配，安全联动用） */
    LOCK_CABINET("CABINET", false),
    /** 电池回池（可继续充电与分配） */
    BATTERY_TO_POOL("BATTERY", true),
    /** 电池转待取回（用户财产的责任边界，不直接报废） */
    BATTERY_PENDING_PICKUP("BATTERY", true),
    /** 解除用户与电池的生效绑定（归属变更的逆操作） */
    UNBIND_USER_BATTERY("BATTERY", true),
    /** 系统自动退款 */
    REFUND_AUTO("RIGHT", true),
    /** 人工退款（需要审批，M4 资金域实现具体通道） */
    REFUND_MANUAL("RIGHT", false),
    /** 退回预占次数（不产生实扣） */
    RELEASE_RIGHT("RIGHT", true),
    /** 实扣次数（预占转已用） */
    DEDUCT_RIGHT("RIGHT", true),
    /** 记一条账实差异（供对账消化） */
    WRITE_DISCREPANCY("ORDER", false),
    /** 生成工单 */
    CREATE_WORK_ORDER("ORDER", false),
    /** 告警升级 */
    ESCALATE_ALARM("ORDER", false),
    /** 冻结订单（禁止继续推进，等人工） */
    FREEZE_ORDER("ORDER", false);

    private final String targetType;
    private final boolean blocking;

    CompensationAction(String targetType, boolean blocking) {
        this.targetType = targetType;
        this.blocking = blocking;
    }

    public String targetType() {
        return targetType;
    }

    /** 是否属于"补偿集未完成就不许落终态"的那一类（I8 的判定范围）。 */
    public boolean blocking() {
        return blocking;
    }

    public String idempotentKeyPrefix() {
        return name();
    }

    /** DDL 允许的全部动作名（供契约测试比对与执行器校验）。 */
    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 未知动作名直接失败：不做"默认按非阻塞处理"这种静默降级。 */
    public static CompensationAction of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
