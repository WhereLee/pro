package com.lrs.buddy.biz.swap.order;

/**
 * 步骤定义（§4.2，细粒度、物理位置）。
 *
 * 步骤是**唯一被物理事件直接推进的对象**，订单态由"哪几个步骤完成"推导。
 * 这层分离的价值在 FI-12 与 FI-13 上最明显：两个故障在订单层完全同形
 * （都停在取电阶段、都没结算），只有步骤层能分辨"电池没被取走"和"取走了但门没关"，
 * 而这两种情况要给用户的话术、要开的工单类型、要做的补偿完全不同。
 */
public enum StepCode {

    OPEN_RETURN(1, "OPEN_SLOT", "door_open", 15),
    // 150s = 提示 90s + 宽限 60s（§4.2）：拆开是为了让"再等等"和"判异常"成为两个决定，
    // 合在一起写 90s 会把"用户还在插"误判成"用户没插"
    WAIT_INSERT(2, null, "battery_detected+door_close", 150),
    VERIFY_RETURN(3, "BATTERY_VERIFY", "battery_detected", 25),
    UNLOCK_OFFER(4, "UNLOCK_SLOT", "door_open", 15),
    WAIT_TAKE(5, null, "battery_taken+door_close", 60),
    SETTLE(6, null, null, 30);

    private final int order;
    private final String expectCmd;
    private final String expectEvent;
    private final int deadlineSeconds;

    StepCode(int order, String expectCmd, String expectEvent, int deadlineSeconds) {
        this.order = order;
        this.expectCmd = expectCmd;
        this.expectEvent = expectEvent;
        this.deadlineSeconds = deadlineSeconds;
    }

    public int order() {
        return order;
    }

    public String expectCmd() {
        return expectCmd;
    }

    public String expectEvent() {
        return expectEvent;
    }

    /**
     * 给用户看的一步叫什么。
     *
     * 文案放在枚举而不是前端：进度条上的“打开归还仓/取出新电池”必须与后端推进的语义同源，
     * 前端自己拍一份就会在步骤改名时错位。
     */
    public String userLabel() {
        return switch (this) {
            case OPEN_RETURN -> "打开归还仓";
            case WAIT_INSERT -> "放入旧电池并关好仓门";
            case VERIFY_RETURN -> "核验电池";
            case UNLOCK_OFFER -> "开取电仓";
            case WAIT_TAKE -> "取出新电池并关好仓门";
            case SETTLE -> "完成结算";
        };
    }

    public int deadlineSeconds() {
        return deadlineSeconds;
    }

    /** 按步骤号取定义（步骤号来自 swap_order_step.step_no，写错必须立刻报错而不是静默当成 S1）。 */
    public static StepCode of(int stepNo) {
        for (StepCode code : values()) {
            if (code.order == stepNo) {
                return code;
            }
        }
        throw new IllegalArgumentException("未知步骤号：" + stepNo);
    }
}
