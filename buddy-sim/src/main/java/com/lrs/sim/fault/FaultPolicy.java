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
 *
 * L2 补齐后共 15 类，覆盖协议 §13 目录里的 FI-01..FI-15（FI-16 是云侧重启，设备侧无需注入）。
 * 每个 Kind 都注明它**要测的是谁的什么行为**——因为"注入点"与"被测点"不是一回事：
 * FI-01/02/04/05/09/11/12/13/14/15 注入在设备行为上；
 * FI-03 注入在应答错误码上；FI-06/07/08 注入在**上行报文本身**（过期/重放/错签），
 * 测的是云侧校验链会不会放行；设备自己收到这三种报文时的拒绝行为在
 * {@link com.lrs.sim.protocol.ValidationChain} 与其单测里覆盖，不重复在这里。
 */
public class FaultPolicy {

    public enum Kind {
        /** FI-01：指令不回任何应答（注意：动作照样发生，只是不上报） */
        DROP_REPLY,
        /** FI-02：同 msgId 应答重复投递（含随机延迟） */
        DUPLICATE_REPLY,
        /** FI-03：应答带失败错误码（E2x/E3x/E4x 三族的分流由云侧负责） */
        NACK_WITH_CODE,
        /** FI-04：ACK 成功后物理事件永不到达（动作真的发生了，只是不上报） */
        SUPPRESS_EVENT,
        /** FI-10：断线重连后，用旧 sessionId 补发迟到应答 */
        STALE_SESSION_REPLY,
        /** FI-13：取走电池但门关事件不来（门未关） */
        LEAVE_DOOR_OPEN,

        /** FI-05：事件乱序——先 `door_close` 再 `door_open`（或制造 seq 缺口） */
        OUT_OF_ORDER_EVENTS,
        /** FI-06：应答/事件发出时已过期（`expireAt` 早于当前时刻），云侧应判 E1003 且不重试 */
        EXPIRED_MESSAGE,
        /** FI-07：同 `sign`+`nonce` 的报文重放一次，云侧应判 E1002 并留安全审计痕迹 */
        REPLAY_MESSAGE,
        /** FI-08：签名错误或签名后篡改 data（两态共用一个 Kind，由 value 指定），云侧应判 E1001 */
        BAD_SIGNATURE,
        /** FI-09：门磁抖动——同仓在极短窗口内反复 open/close */
        DOOR_FLAP,
        /** FI-11：投入旧电池后不关门（人不在了） */
        INSERT_NO_CLOSE,
        /** FI-12：弹仓未取走却把门关上（电池还留在仓里） */
        NOT_TAKEN_THEN_CLOSE,
        /** FI-14：安全告警（value 给告警码，如 SMOKE / TEMP_HIGH） */
        SAFETY_ALARM,
        /** FI-15：上报不合物模型（value 给属性名，类型故意写错或写未知键） */
        BAD_THING_MODEL
    }

    /**
     * 触发器。
     *
     * `afterStep` 保留 L1 语义（第几条报文/第几步之后生效）；`times` 与 `value` 是 L2 新增参数：
     * 抖动次数、告警码、坏属性名这类"注入什么值"必须由用例写明，
     * 不能让执行侧猜一个默认值——猜出来的 FI-14 到底注入的是烟感还是过温，事后无法回答。
     */
    public record Trigger(Kind kind, String cmdCode, int afterStep, int repeatTimes, String errorCode,
                          int times, String value) {

        /** L1 兼容构造：旧用例只给 5 个参数，times/value 走默认值。 */
        public Trigger(Kind kind, String cmdCode, int afterStep, int repeatTimes, String errorCode) {
            this(kind, cmdCode, afterStep, repeatTimes, errorCode, 0, null);
        }
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

    /** 已编排的触发器条数（控制面回读用：“注入成功了没有”应该能被问出来，而不是只能从行为反推）。 */
    public int triggerCount() {
        return triggers.size();
    }

    public java.util.List<Trigger> triggers() {
        return java.util.List.copyOf(triggers);
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

    /** FI-05：事件顺序倒挂（先 close 后 open）。 */
    public boolean reorderEvents(String cmdCode) {
        return matches(Kind.OUT_OF_ORDER_EVENTS, cmdCode);
    }

    /** FI-06：报文发出即已过期，返回过期秒数（0 表示不注入）。 */
    public int expiredSeconds(String cmdCode) {
        return intParam(Kind.EXPIRED_MESSAGE, cmdCode, 0);
    }

    /** FI-07：重放次数（0 表示不注入）。 */
    public int replayTimes(String cmdCode) {
        return intParam(Kind.REPLAY_MESSAGE, cmdCode, 0);
    }

    /** FI-08：坏签名/篡改，返回注入方式（SIGN 或 TAMPER），null 表示不注入。 */
    public String badSignatureMode(String cmdCode) {
        return triggers.stream().filter(t -> t.kind() == Kind.BAD_SIGNATURE && matchesCmd(t, cmdCode))
                .map(Trigger::value).findFirst().orElse(null);
    }

    /** FI-09：抖动次数（0 表示不注入）。 */
    public int flapTimes(String cmdCode) {
        return intParam(Kind.DOOR_FLAP, cmdCode, 0);
    }

    /** FI-11：投入后不关门。 */
    public boolean insertWithoutClose() {
        return has(Kind.INSERT_NO_CLOSE);
    }

    /** FI-12：未取走却关门。 */
    public boolean closeWithoutTake() {
        return has(Kind.NOT_TAKEN_THEN_CLOSE);
    }

    /** FI-14：告警码（SMOKE / TEMP_HIGH / WATER / LEAK），null 表示不注入。 */
    public String alarmCode(String cmdCode) {
        return triggers.stream().filter(t -> t.kind() == Kind.SAFETY_ALARM && matchesCmd(t, cmdCode))
                .map(Trigger::value).findFirst().orElse(null);
    }

    /** FI-15：坏物模型属性名，null 表示不注入。 */
    public String badProperty(String cmdCode) {
        return triggers.stream().filter(t -> t.kind() == Kind.BAD_THING_MODEL && matchesCmd(t, cmdCode))
                .map(Trigger::value).findFirst().orElse(null);
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

    private int intParam(Kind kind, String cmdCode, int fallback) {
        return triggers.stream().filter(t -> t.kind() == kind && matchesCmd(t, cmdCode))
                .map(t -> t.times() > 0 ? t.times() : t.repeatTimes()).findFirst().orElse(fallback);
    }

    private boolean matches(Kind kind, String cmdCode) {
        return triggers.stream().anyMatch(trigger -> trigger.kind() == kind && matchesCmd(trigger, cmdCode));
    }

    private static boolean matchesCmd(Trigger trigger, String cmdCode) {
        return trigger.cmdCode() == null || trigger.cmdCode().equals(cmdCode);
    }
}
