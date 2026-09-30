package com.lrs.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrs.sim.device.CabinetDevice;
import com.lrs.sim.fault.FaultPolicy;
import com.lrs.sim.protocol.SimProtocol;
import com.lrs.sim.protocol.ValidationChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验链与物理模型的自证。
 *
 * 为什么模拟器本身必须有测试：如果"假装发错报文"的实现是错的（例如重复指令其实没重放应答），
 * 那么 M3 整套故障矩阵的结论都是假的 —— 故障注入器自己是坏的，比没有故障注入更危险。
 */
class SimValidationChainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MASTER = "chain-test-master-secret";

    private final ValidationChain chain = new ValidationChain(SimProtocol.messageSecret(MASTER),
            DeviceLink.KNOWN_COMMANDS);
    private final AtomicInteger executions = new AtomicInteger();

    private byte[] command(String msgId, String nonce, long seq, long expireAt, String cmd) {
        ObjectNode data = MAPPER.createObjectNode().put("slotNo", 3);
        SimProtocol.Envelope unsigned = new SimProtocol.Envelope("1.0", msgId, System.currentTimeMillis(),
                expireAt, nonce, null, "s-1", "SWAP-CAB-8::CAB1", null, seq, cmd, null, data, null);
        String sign = SimProtocol.sign(SimProtocol.messageSecret(MASTER), unsigned);
        SimProtocol.Envelope signed = new SimProtocol.Envelope("1.0", msgId, unsigned.issuedAt(), expireAt, nonce,
                null, "s-1", unsigned.from(), null, seq, cmd, null, data, sign);
        return SimProtocol.encode(signed);
    }

    private ValidationChain.Outcome accept(byte[] payload) {
        return chain.accept(payload, System.currentTimeMillis(), true, envelope -> {
            executions.incrementAndGet();
            ObjectNode reply = MAPPER.createObjectNode().put("cmdId", envelope.msgId()).put("ok", true);
            return new SimProtocol.Envelope("1.0", "reply-" + envelope.msgId(), System.currentTimeMillis(),
                    envelope.expireAt(), envelope.nonce(), null, envelope.sessionId(), envelope.from(), null,
                    envelope.seq(), envelope.cmd(), "OK", reply, null);
        });
    }

    @Test
    @DisplayName("首条指令通过校验并真的执行一次")
    void firstCommandExecutes() {
        ValidationChain.Outcome outcome = accept(command("01JZA000000000000000000001", "n-a", 1,
                System.currentTimeMillis() + 60_000L, "OPEN_SLOT"));
        assertThat(outcome.verdict()).isEqualTo(ValidationChain.Verdict.ACCEPT);
        assertThat(outcome.executed()).isTrue();
        assertThat(executions).hasValue(1);
    }

    @Test
    @DisplayName("重复 msgId 必须重放上次应答，而不是静默不回")
    void duplicateRepliesWithPreviousResult() {
        byte[] payload = command("01JZA000000000000000000002", "n-b", 2,
                System.currentTimeMillis() + 60_000L, "OPEN_SLOT");
        ValidationChain.Outcome first = accept(payload);
        ValidationChain.Outcome second = accept(payload);

        assertThat(first.verdict()).isEqualTo(ValidationChain.Verdict.ACCEPT);
        assertThat(second.verdict()).isEqualTo(ValidationChain.Verdict.DUPLICATE_REPLAYED);
        assertThat(executions).as("重复指令不得二次执行，否则门会被开两次").hasValue(1);
        assertThat(second.envelope()).as("必须把上次应答原样重放，否则云侧会误判超时").isNotNull();
        assertThat(second.envelope().code()).isEqualTo("OK");
    }

    @Test
    @DisplayName("过期指令被拒且不执行")
    void expiredCommandIsRejected() {
        ValidationChain.Outcome outcome = accept(command("01JZA000000000000000000003", "n-c", 3,
                System.currentTimeMillis() - 1000L, "OPEN_SLOT"));
        assertThat(outcome.verdict()).isEqualTo(ValidationChain.Verdict.EXPIRED);
        assertThat(outcome.executed()).isFalse();
    }

    @Test
    @DisplayName("重放同一 nonce 被拒")
    void replayedNonceIsRejected() {
        accept(command("01JZA000000000000000000004", "n-dup", 4, System.currentTimeMillis() + 60_000L,
                "QUERY_STATUS"));
        ValidationChain.Outcome replay = accept(command("01JZA000000000000000000005", "n-dup", 5,
                System.currentTimeMillis() + 60_000L, "QUERY_STATUS"));
        assertThat(replay.verdict()).isEqualTo(ValidationChain.Verdict.REPLAY);
    }

    @Test
    @DisplayName("未知指令显式拒绝，不静默忽略")
    void unknownCommandIsRejectedExplicitly() {
        ValidationChain.Outcome outcome = accept(command("01JZA000000000000000000006", "n-e", 6,
                System.currentTimeMillis() + 60_000L, "NOT_A_REAL_CMD"));
        assertThat(outcome.verdict()).as("静默忽略会让云侧误判为设备接受但未响应")
                .isEqualTo(ValidationChain.Verdict.UNKNOWN_CMD);
    }

    @Test
    @DisplayName("伪造签名的指令不执行")
    void forgedSignatureIsRejected() {
        byte[] forged = command("01JZA000000000000000000007", "n-f", 7, System.currentTimeMillis() + 60_000L,
                "OPEN_SLOT");
        // 换掉 payload 里的 slotNo 但保留签名
        SimProtocol.Envelope envelope = SimProtocol.decode(forged);
        ObjectNode tamperedData = MAPPER.createObjectNode().put("slotNo", 9);
        byte[] tampered = SimProtocol.encode(new SimProtocol.Envelope(envelope.v(), envelope.msgId(),
                envelope.issuedAt(), envelope.expireAt(), envelope.nonce(), envelope.traceId(), envelope.sessionId(),
                envelope.from(), null, envelope.seq(), envelope.cmd(), null, tamperedData, envelope.sign()));

        assertThat(accept(tampered).verdict()).isEqualTo(ValidationChain.Verdict.REJECT_SIGN);
    }

    @Test
    @DisplayName("物理模型状态迁移严格：故障仓位不能开门，SOC 只能按脚本推进")
    void physicsTransitionsAreStrict() {
        CabinetDevice cabinet = new CabinetDevice("CAB-P", 4, 30.0);
        assertThat(cabinet.openDoor(2)).isTrue();
        assertThat(cabinet.closeDoor(2)).isTrue();
        cabinet.jamDoor(3);
        assertThat(cabinet.openDoor(3)).as("门已故障时不能报成功开门").isFalse();

        cabinet.insert(1, "BAT1", 40, 30.0);
        cabinet.tickCharge(10);
        assertThat(cabinet.slot(1).soc).isEqualTo(50);
        cabinet.tickCharge(60);
        assertThat(cabinet.slot(1).soc).as("SOC 不得超过 100").isEqualTo(100);
        assertThat(cabinet.slot(1).charge).isEqualTo(CabinetDevice.Charge.FULL);

        CabinetDevice.Battery taken = cabinet.take(1);
        assertThat(taken.code()).isEqualTo("BAT1");
        assertThat(cabinet.take(1)).as("空仓位取不出电池").isNull();
    }

    @Test
    @DisplayName("故障策略按 cmdCode 精确命中，且注入被记录")
    void faultPolicyMatchesByCommand() {
        FaultPolicy faults = new FaultPolicy(7L)
                .add(new FaultPolicy.Trigger(FaultPolicy.Kind.DROP_REPLY, "OPEN_SLOT", 0, 1, null))
                .add(new FaultPolicy.Trigger(FaultPolicy.Kind.DUPLICATE_REPLY, null, 0, 3, null))
                .add(new FaultPolicy.Trigger(FaultPolicy.Kind.NACK_WITH_CODE, "UNLOCK_SLOT", 0, 1, "E3002"));

        assertThat(faults.swallowReply("OPEN_SLOT")).isTrue();
        assertThat(faults.swallowReply("QUERY_STATUS")).isFalse();
        assertThat(faults.replyRepeat("QUERY_STATUS")).isEqualTo(3);
        assertThat(faults.nackCode("UNLOCK_SLOT")).isEqualTo("E3002");
        faults.recordFired("test");
        assertThat(faults.fired()).containsExactly("test");
    }

    @Test
    @DisplayName("同 seed 的抖动序列可重复（否则故障回归不可复现）")
    void jitterIsDeterministicPerSeed() {
        assertThat(new FaultPolicy(99L).jitterMillis(100)).isEqualTo(new FaultPolicy(99L).jitterMillis(100));
        assertThat(List.of(1, 2, 3)).hasSize(3);
    }

    @Test
    @DisplayName("副作用指令列表与协议一致")
    void sideEffectCommandsAreDeclared() {
        assertThat(ValidationChain.isSideEffect("OPEN_SLOT")).isTrue();
        assertThat(ValidationChain.isSideEffect("QUERY_STATUS")).isFalse();
    }
}
