package com.lrs.buddy.biz.swap.display;

import com.lrs.buddy.biz.swap.order.OrderState;

import java.util.EnumMap;
import java.util.Map;

/**
 * C 端展示态（订单态到用户可见语义的唯一映射处）。
 *
 * 为什么映射必须在后端而不是前端：前端拿订单态自己拼文案时，"未知"很容易被顺手归进
 * "失败"分支（因为 UI 只有成功/失败两种样式）。而**把未知显示成失败是资金与现场双重事故**：
 * 用户以为没换成会去取回旧电池或重复下单，柜机侧其实可能已经开过仓。
 * 所以这里把语义定死，前端只渲染 {@code tone} 和文案，不做任何判断。
 *
 * 三条硬规则（有测试逐条钉）：
 * <ul>
 *   <li>{@code UNCONFIRMED} / {@code SUSPENDED} 的 tone 一律是 {@code warn}，**不得是 danger**：
 *       含义是"正在核实/需要你确认"，不是"你失败了"</li>
 *   <li>{@code FAILED_MANUAL} 是"人工核资中"，钱还没定论，也不得渲染成失败</li>
 *   <li>只有 {@code REJECTED} / {@code ABORTED} 才是明确的负向终态</li>
 * </ul>
 */
public enum DisplayState {

    SUBMITTING("提交中", "progress", "正在校验资格与仓位，请稍候"),
    WAIT_OPEN("请打开归还仓", "progress", "柜门即将打开，请把旧电池放入"),
    RETURN_IN_PROGRESS("归还中", "progress", "放入电池后请关好仓门"),
    VERIFYING("核验中", "progress", "正在确认电池状态，无需操作"),
    WAIT_TAKE("请取出新电池", "progress", "取电仓已开，请取出电池并关门"),
    TAKING("取件中", "progress", "请取出新电池"),
    SETTLING("结算中", "progress", "正在扣减权益并完成归属变更"),
    NEED_CONFIRM("等待你确认", "warn", "仓门状态未确认，请确认已关好仓门后点下方按钮"),
    UNKNOWN("核实中", "warn", "柜机回传暂不完整，系统正在核实实际状态，请勿重复下单"),
    CANCEL_PROCESSING("取消处理中", "warn", "正在回滚预占与权益，请稍候"),
    REVIEWING("人工核资中", "warn", "该笔订单需人工核实，权益暂未结算，请留意通知"),
    SUCCESS("换电完成", "success", "新电池已归你使用，感谢配合"),
    REJECTED("未能下单", "danger", "资格或仓位校验未通过，权益未扣减"),
    CANCELLED("订单已取消", "danger", "本次换电已取消，权益已退回");

    /** 只允许这些负向终态被渲染成"失败"样式。 */
    private static final Map<OrderState, DisplayState> MAPPING = new EnumMap<>(OrderState.class);

    static {
        MAPPING.put(OrderState.CREATED, SUBMITTING);
        MAPPING.put(OrderState.AUTHORIZED, WAIT_OPEN);
        MAPPING.put(OrderState.RETURNING, RETURN_IN_PROGRESS);
        MAPPING.put(OrderState.RETURNED, VERIFYING);
        MAPPING.put(OrderState.VERIFYING, VERIFYING);
        MAPPING.put(OrderState.OFFERING, WAIT_TAKE);
        MAPPING.put(OrderState.TAKEN, TAKING);
        MAPPING.put(OrderState.SETTLING, SETTLING);
        MAPPING.put(OrderState.SUSPENDED, NEED_CONFIRM);
        MAPPING.put(OrderState.UNCONFIRMED, UNKNOWN);
        MAPPING.put(OrderState.ABORTING, CANCEL_PROCESSING);
        MAPPING.put(OrderState.FAILED_MANUAL, REVIEWING);
        MAPPING.put(OrderState.COMPLETED, SUCCESS);
        MAPPING.put(OrderState.REJECTED, REJECTED);
        MAPPING.put(OrderState.ABORTED, CANCELLED);
    }

    private final String label;
    private final String tone;
    private final String hint;

    DisplayState(String label, String tone, String hint) {
        this.label = label;
        this.tone = tone;
        this.hint = hint;
    }

    /** 订单态 → 展示态。映射缺失直接抛错：宁可 500 也不让某个新状态在前端悄悄落到"失败"分支。 */
    public static DisplayState of(OrderState state) {
        DisplayState display = MAPPING.get(state);
        if (display == null) {
            throw new IllegalStateException("订单态没有 C 端展示映射，必须在此登记：" + state);
        }
        return display;
    }

    public static DisplayState of(String stateName) {
        return of(OrderState.valueOf(stateName));
    }

    public boolean terminal() {
        return this == SUCCESS || this == REJECTED || this == CANCELLED || this == REVIEWING;
    }

    /** 是否允许"再下一单"：只有明确负向终态与完成态可以（UNKNOWN/NEED_CONFIRM 不可以）。 */
    public boolean canReorder() {
        return this == SUCCESS || this == REJECTED || this == CANCELLED;
    }

    public String label() {
        return label;
    }

    public String tone() {
        return tone;
    }

    public String hint() {
        return hint;
    }
}
