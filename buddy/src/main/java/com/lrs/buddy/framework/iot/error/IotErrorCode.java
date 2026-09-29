package com.lrs.buddy.framework.iot.error;

/**
 * 接入层错误码（swap-protocol.md §8）。
 *
 * 分段语义决定后续处置路径，不是命名美化：
 * E0 协议类与 E1 安全类是"报文根本不该被处理"，E2/E4 是"状态或业务不允许"（可换仓/需人工核），
 * E3 是"硬件故障"（必须开工单，绝不能让用户重试解决）。
 */
public enum IotErrorCode {

    OK("OK", Step.NONE),

    E0001("E0001", Step.PARSE),
    E0002("E0002", Step.VERSION),
    E0003("E0003", Step.COMMAND),

    E1001("E1001", Step.SIGN),
    E1002("E1002", Step.REPLAY),
    E1003("E1003", Step.EXPIRY),
    E1004("E1004", Step.AUTHZ),

    E2001("E2001", Step.PRECONDITION),
    E2002("E2002", Step.PRECONDITION),
    E2003("E2003", Step.PRECONDITION),
    E2004("E2004", Step.PRECONDITION),

    E3001("E3001", Step.EXECUTE),
    E3002("E3002", Step.EXECUTE),
    E3003("E3003", Step.EXECUTE),
    E3004("E3004", Step.EXECUTE),

    E4001("E4001", Step.VERIFY),
    E4002("E4002", Step.VERIFY),
    E4003("E4003", Step.VERIFY),
    E4004("E4004", Step.VERIFY),

    /** 云侧内部码：设备与会话不匹配（协议 §7.3 丢弃迟到应答）。 */
    S_SESSION_STALE("S1001", Step.SESSION),
    /** 云侧内部码：设备离线，指令未下发。 */
    S_DEVICE_OFFLINE("S1002", Step.DISPATCH),
    /** 云侧内部码：等应答时超时（不等于未发生，见协议 §4.2）。 */
    S_TIMEOUT("S1003", Step.WAIT_REPLY);

    /** 校验链步骤，对应协议 §6 的十步；落 iot_message_log.reject_step 供排障定位。 */
    public enum Step {
        NONE, PARSE, VERSION, COMMAND, SIGN, EXPIRY, REPLAY, AUTHZ, SEQ, DEDUP, SESSION, PRECONDITION,
        DISPATCH, WAIT_REPLY, VERIFY, EXECUTE
    }

    private final String code;
    private final Step step;

    IotErrorCode(String code, Step step) {
        this.code = code;
        this.step = step;
    }

    public String code() {
        return code;
    }

    public Step step() {
        return step;
    }

    public boolean isSuccess() {
        return this == OK;
    }

    /** 是否属于安全类（进风控计数并采样告警，不写明细表以免被爆破打成写放大）。 */
    public boolean securityRelated() {
        return step == Step.SIGN || step == Step.REPLAY || step == Step.AUTHZ;
    }

    /** 是否允许云侧有限重试：只读或值收敛类才允许（协议 §4.1 铁律一）。 */
    public boolean retryable() {
        return this == S_TIMEOUT || this == E2004;
    }

    public static IotErrorCode fromCode(String code) {
        if (code == null || code.isBlank() || "OK".equalsIgnoreCase(code)) {
            return OK;
        }
        for (IotErrorCode value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return E0001;
    }
}
