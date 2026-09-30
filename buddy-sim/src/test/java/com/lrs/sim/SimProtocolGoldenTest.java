package com.lrs.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrs.sim.protocol.SimProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备侧的协议 golden 契约测试：读与云端**同一批样本文件**，用自己的独立实现断言同样的期望值。
 *
 * 两侧都对才叫约定成立。任何一侧单独改序列化方式，这里都会立刻红 ——
 * 这正是"零代码共享"想要的效果（swap-simulator.md §3）。
 */
class SimProtocolGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static List<Path> samples() throws Exception {
        try (Stream<Path> files = Files.list(sampleDir())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private static Path sampleDir() {
        Path direct = Path.of("../protocol/v1/samples");
        return Files.isDirectory(direct) ? direct : Path.of("../../protocol/v1/samples");
    }

    @Test
    @DisplayName("必须能找到共享样本目录，否则本测试等于没跑")
    void samplesExist() {
        assertThat(Files.isDirectory(sampleDir())).as("样本目录：" + sampleDir().toAbsolutePath()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("规范化字节与签名基串与云端约定一致")
    void goldenConventions(Path file) throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        String name = root.path("name").asText();
        JsonNode envelopeNode = root.path("envelope");
        JsonNode expected = root.path("expected");

        SimProtocol.Envelope envelope = SimProtocol.decode(MAPPER.writeValueAsBytes(envelopeNode));

        assertThat(envelope.msgId()).as(name + " msgId").isEqualTo(envelopeNode.path("msgId").asText());
        assertThat(envelope.expireAt()).as(name + " expireAt").isEqualTo(envelopeNode.path("expireAt").asLong());
        assertThat(envelope.sessionId()).as(name + " sessionId").isEqualTo(envelopeNode.path("sessionId").asText());

        assertThat(SimProtocol.canonicalText(envelope.data())).as(name + " 规范化字节")
                .isEqualTo(expected.path("canonicalData").asText());
        assertThat(SimProtocol.signBase(envelope)).as(name + " 签名基串前缀")
                .startsWith(expected.path("signBasePrefix").asText());
    }

    @Test
    @DisplayName("签名可自验，且错密钥无法通过")
    void signAndVerifyRoundTrip() {
        SimProtocol.Envelope unsigned = new SimProtocol.Envelope("1.0", "01JZTEST0000000000000000AA", 1L, 2L,
                "n1", null, "s1", "SWAP-CAB-8::CAB1", null, 1L, "OPEN_SLOT", null,
                MAPPER.createObjectNode().put("slotNo", 3), null);
        String secret = SimProtocol.messageSecret("master-a");
        SimProtocol.Envelope signed = new SimProtocol.Envelope(unsigned.v(), unsigned.msgId(), unsigned.issuedAt(),
                unsigned.expireAt(), unsigned.nonce(), unsigned.traceId(), unsigned.sessionId(), unsigned.from(),
                null, unsigned.seq(), unsigned.cmd(), unsigned.code(), unsigned.data(),
                SimProtocol.sign(secret, unsigned));

        assertThat(SimProtocol.verify(secret, signed)).isTrue();
        assertThat(SimProtocol.verify(SimProtocol.messageSecret("master-b"), signed))
                .as("换密钥必须验不过，否则伪造报文与正常报文无法区分").isFalse();

        SimProtocol.Envelope tampered = new SimProtocol.Envelope(signed.v(), signed.msgId(), signed.issuedAt(),
                signed.expireAt(), signed.nonce(), signed.traceId(), signed.sessionId(), signed.from(), null,
                signed.seq(), "UNLOCK_SLOT", signed.code(), signed.data(), signed.sign());
        assertThat(SimProtocol.verify(secret, tampered))
                .as("改 cmd 不改 data 必须被签名挡住（只签 data 的实现会在这里放过它）").isFalse();
    }
}
