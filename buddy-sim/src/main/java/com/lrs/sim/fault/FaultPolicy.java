package com.lrs.sim.fault;

import java.util.Random;

/**
 * 故障注入策略（L1 先实现 M1/M2 验收必需的 5 项，L2 在 M3 补满 16 项）。
 *
 * 三条设计要求（swap-simulator.md §7）：
 * 1 可**精确编排**：故障绑定到"某个指令码上"或"第 N 条报文上"，不能只用百分比概率，
 *   否则回归断言无意义（同一次红到底是不是同一个原因都说不清）。
 * 2 可重复：所有随机都吃同一个 seed。
 * 3 可观测：每次触发都记一条记录，供"双端归因"比对。
 */
public class FaultPolicy {

    public enum Kind {
        /** FI-01：指令不回任何应答 */
        DROP_REPLY,
        /** FI-02：同 msgId 应答重复投递（含随机延迟） */
        DUPLICATE_REPLY,
        /** FI-03：应答带失败错误码 */
        NACK_WITH_CODE,
        /** FI-04：ACK 成功后物理事件永不到达（动作真的发生了，只是不上报） */
        SUPPRESS_EVENT,
        /** FI-10：断线重连后，用旧 sessionId 补发迟到应答 */
        STALE_SESSION_REPLY,
        /** FI-13：取走电池但门关事件不来（门未关） */
        LEAVE_DOOR_OPEN
    }

    public record Trigger(Kind kind, String cmdCode, int afterStep, int repeatTimes, String errorCode) {
    }

    private final java.util.List<Trigger> triggers = new java.util.ArrayList<>();
    private final Random random;
    private final java.util.List<String> fired = new java.util.ArrayList<>();

    public FaultPolicy(long seed) {
        this.random = new Random(seed);
    }

    public FaultPolicy add(Trigger trigger) {
        triggers.add(trigger);
        return this;
    }

    public boolean has(Kind kind) {
        return triggers.stream().anyMatch(trigger -> trigger.kind() == kind);
    }

    public boolean swallowReply(String cmdCode) {
        return matches(Kind.DROP_REPLY, cmdCode);
    }

    public boolean suppressEvent(String cmdCode) {
        return matches(Kind.SUPPRESS_EVENT, cmdCode);
    }

    public boolean leaveDoorOpen(String cmdCode) {
        return matches(Kind.LEAVE_DOOR_OPEN, cmdCode);
    }

    public boolean staleSession(String cmdCode) {
        return matches(Kind.STALE_SESSION_REPLY, cmdCode);
    }

    public int replyRepeat(String cmdCode) {
        return triggers.stream().filter(t -> t.kind() == Kind.DUPLICATE_REPLY && matchesCmd(t, cmdCode))
                .mapToInt(Trigger::repeatTimes).max().orElse(1);
    }

    public String nackCode(String cmdCode) {
        return triggers.stream().filter(t -> t.kind() == Kind.NACK_WITH_CODE && matchesCmd(t, cmdCode))
                .map(Trigger::errorCode).findFirst().orElse(null);
    }

    public int jitterMillis(int bound) {
        return bound <= 0 ? 0 : random.nextInt(bound);
    }

    /** 注入记录：让"这次故障确实发生过"可被外部核对，而不是靠日志翻找。 */
    public void recordFired(String description) {
        fired.add(description);
    }

    public java.util.List<String> fired() {
        return java.util.List.copyOf(fired);
    }

    private boolean matches(Kind kind, String cmdCode) {
        return triggers.stream().anyMatch(trigger -> trigger.kind() == kind && matchesCmd(trigger, cmdCode));
    }

    private static boolean matchesCmd(Trigger trigger, String cmdCode) {
        return trigger.cmdCode() == null || trigger.cmdCode().equals(cmdCode);
    }
}
