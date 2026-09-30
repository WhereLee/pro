package com.lrs.buddy.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrs.buddy.framework.iot.envelope.Envelope;
import com.lrs.buddy.framework.iot.envelope.JsonPayloadCodec;
import com.lrs.buddy.framework.iot.security.DeviceSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 协议 golden 样本契约测试（云侧这一半）。
 *
 * 为什么值得单独立一个测试：信封的字节级约定是跨端协议，任何一侧"顺手改一下序列化"都会让另一侧解不开，
 * 而这种改动在自己单侧的单测里完全看不出来。
 * buddy-sim 里有一份**独立实现**的同名测试，读同一批样本文件 —— 两边都对才算约定成立（swap-simulator.md §3）。
 *
 * 这里刻意断言 canonicalData 的**精确字节**而不是"解析后对象相等"：
 * 签名摘要建立在字节之上，字段顺序、空白、转义的差异都会让两端签名互不认可。
 */
class SwapProtocolGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonPayloadCodec CODEC = new JsonPayloadCodec();
    private static final DeviceSecrets SECRETS = new DeviceSecrets(CODEC);

    static List<Path> samples() throws Exception {
        Path dir = sampleDir();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private static Path sampleDir() {
        Path direct = Paths.get("../protocol/v1/samples");
        if (Files.isDirectory(direct)) {
            return direct.normalize();
        }
        return Paths.get("protocol/v1/samples").normalize();
    }

    @Test
    @DisplayName("必须能找到样本，否则本测试等于没跑")
    void samplesExist() {
        assertThat(Files.isDirectory(sampleDir())).as("样本目录：" + sampleDir().toAbsolutePath()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("每条样本的解码结果、规范化字节与签名基串都与约定一致")
    void goldenSampleConventions(Path file) throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        String name = root.path("name").asText();
        JsonNode envelopeNode = root.path("envelope");
        JsonNode expected = root.path("expected");

        Envelope envelope = CODEC.decode(MAPPER.writeValueAsBytes(envelopeNode));

        assertThat(envelope.v()).as(name + " 版本").isEqualTo("1.0");
        assertThat(envelope.msgId()).as(name + " msgId").isEqualTo(envelopeNode.path("msgId").asText());
        assertThat(envelope.expireAt()).as(name + " expireAt").isEqualTo(envelopeNode.path("expireAt").asLong());
        assertThat(envelope.sessionId()).as(name + " sessionId").isEqualTo(envelopeNode.path("sessionId").asText());
        assertThat(envelope.cmd() == null ? "" : envelope.cmd()).as(name + " cmd").isEqualTo(textOrEmpty(envelopeNode, "cmd"));
        assertThat(envelope.code() == null ? "" : envelope.code()).as(name + " code").isEqualTo(textOrEmpty(envelopeNode, "code"));

        String canonical = new String(CODEC.canonicalData(envelope.data()), StandardCharsets.UTF_8);
        assertThat(canonical).as(name + " 规范化字节（决定签名摘要）")
                .isEqualTo(expected.path("canonicalData").asText());

        String signBase = SECRETS.signBase(envelope);
        assertThat(signBase).as(name + " 签名基串前缀").startsWith(expected.path("signBasePrefix").asText());
        // 基串共 6 段：cmd / msgId / issuedAt / expireAt / nonce / data 摘要
        assertThat(signBase.split("\n", -1)).as(name + " 签名基串段数").hasSize(6);
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }
}
