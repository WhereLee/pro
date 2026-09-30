package com.lrs.sim.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备侧校验链（协议 §6，严格按序）。
 *
 * 两条实现要点，缺任何一条都会让故障注入变成自欺：
 * 1 **重复指令不是"忽略"，而是"重放上次执行结果"**。若直接 return 不响应，
 *    云侧拿不到应答会误判超时并触发反查，把一次本来成功的动作搅成糊账。
 * 2 有效期判定不信设备时钟：本实现支持注入一个"可信服务端时间"，
 *    拿不到时**保守拒绝执行非幂等指令**（设备 RTC 可被现场人员改，绝不能当真相）。
 */
public class ValidationChain {

    public enum Verdict {
        ACCEPT, REJECT_SIGN, REPLAY, EXPIRED, DUPLICATE_REPLAYED, UNKNOWN_CMD, BAD_PAYLOAD, PRECONDITION
    }

    public record Outcome(Verdict verdict, String msgId, SimProtocol.Envelope envelope, boolean executed) {
    }

    /** 保留最近 N 条已处理指令的应答，用于重放。7 天/上限都在协议里，超出的重复包按新包处理。 */
    private static final int PROCESSED_CAP = 512;
    private static final int NONCE_CAP = 2048;

    private final String msgSecret;
    private final List<String> knownCommands;
    private final Map<String, SimProtocol.Envelope> processed = new LinkedHashMap<>();
    private final Map<String, Boolean> seenNonces = new LinkedHashMap<>();
    private long lastSeq = -1;

    public ValidationChain(String msgSecret, List<String> knownCommands) {
        this.msgSecret = msgSecret;
        this.knownCommands = knownCommands;
    }

    /**
     * @param trustedNowMs 云侧下发的可信时间（无则传 null，按保守策略拒绝非幂等指令）
     * @param executor     通过校验后的执行动作，返回应答载荷
     */
    public Outcome accept(byte[] payload, long trustedNowMs, boolean clockTrusted,
                          java.util.function.Function<SimProtocol.Envelope, SimProtocol.Envelope> executor) {
        SimProtocol.Envelope envelope;
        try {
            envelope = SimProtocol.decode(payload);
        } catch (RuntimeException e) {
            return new Outcome(Verdict.BAD_PAYLOAD, null, null, false);
        }
        if (envelope.cmd() != null && !knownCommands.contains(envelope.cmd())) {
            // 未知指令必须显式拒绝：静默忽略会让云侧误判"设备接受但未响应"
            return new Outcome(Verdict.UNKNOWN_CMD, envelope.msgId(), envelope, false);
        }
        if (!SimProtocol.verify(msgSecret, envelope)) {
            return new Outcome(Verdict.REJECT_SIGN, envelope.msgId(), envelope, false);
        }
        if (!clockTrusted && isSideEffect(envelope.cmd())) {
            return new Outcome(Verdict.EXPIRED, envelope.msgId(), envelope, false);
        }
        if (envelope.expireAt() != null && trustedNowMs > envelope.expireAt()) {
            return new Outcome(Verdict.EXPIRED, envelope.msgId(), envelope, false);
        }
        // putIfAbsent 返回 null 才是"首次出现"；上一行写成 !putIfAbsent(...) 会直接 NPE，
        // 这类低级错靠模拟器自己的单测才入得出来 —— 故障注入器自己是坏的比没有它更危险。
        // 幂等优先于 nonce：QoS1 重投与构造重放在“同 msgId”这一层形状相同，
        // 先按幂等处理才能重放应答；nonce 只负责“换了 msgId 但复用旧 nonce”这种情况。
        // （协议 §6 的步序已同步修正：msgId 去重前置到 nonce 前面）
        SimProtocol.Envelope previous = processed.get(envelope.msgId());
        if (previous != null) {
            return new Outcome(Verdict.DUPLICATE_REPLAYED, envelope.msgId(), previous, false);
        }
        if (envelope.nonce() == null || rememberNonce(envelope.nonce())) {
            return new Outcome(Verdict.REPLAY, envelope.msgId(), envelope, false);
        }
        if (envelope.seq() != null) {
            if (envelope.seq() <= lastSeq) {
                // 乱序只记录不拒绝：QoS1 的重投本来就带旧 seq
                return new Outcome(Verdict.DUPLICATE_REPLAYED, envelope.msgId(), envelope, false);
            }
            lastSeq = envelope.seq();
        }
        SimProtocol.Envelope reply = executor.apply(envelope);
        remember(envelope.msgId(), reply);
        return new Outcome(Verdict.ACCEPT, envelope.msgId(), reply, true);
    }

    /** 前置条件由设备状态机判定，失败时按 PRECONDITION 回错误码。 */
    public Outcome rejectPrecondition(SimProtocol.Envelope envelope) {
        return new Outcome(Verdict.PRECONDITION, envelope.msgId(), envelope, false);
    }

    /** nonce 窗口有界：无界集合在长跑压测里就是一个内存泄漏，比误判一次重放更难查。 */
    private boolean rememberNonce(String nonce) {
        Boolean previous = seenNonces.put(nonce, Boolean.TRUE);
        if (seenNonces.size() > NONCE_CAP) {
            String oldest = seenNonces.keySet().iterator().next();
            seenNonces.remove(oldest);
        }
        return previous != null;
    }

    private void remember(String msgId, SimProtocol.Envelope reply) {
        if (msgId == null) {
            return;
        }
        processed.put(msgId, reply);
        if (processed.size() > PROCESSED_CAP) {
            String oldest = processed.keySet().iterator().next();
            processed.remove(oldest);
        }
    }

    /** 有副作用、重复执行会造成资产损失的指令（协议 §4.1）。 */
    public static boolean isSideEffect(String cmd) {
        return cmd != null && List.of("OPEN_SLOT", "UNLOCK_SLOT", "REBOOT", "OTA_PUSH").contains(cmd);
    }

    public List<String> processedMsgIds() {
        return new ArrayList<>(processed.keySet());
    }

    public String dataText(JsonNode data, String field) {
        if (data == null || data.isNull()) {
            return null;
        }
        JsonNode value = data.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
