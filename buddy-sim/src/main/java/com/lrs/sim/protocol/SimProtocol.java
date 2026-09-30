package com.lrs.sim.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * 设备侧协议实现：信封、编解码、密钥派生、签名。
 *
 * 这是云端 framework/iot 的**第二份独立实现**，两边不共享一行代码（只共享 protocol/ 目录里的样本文件）。
 * 理由不是洁癖：如果 canonical 化写错（例如嵌套对象没递归排序），而设备侧复用云端同一个类，
 * 两端会错得完全一致，所有测试全绿而协议是坏的。独立实现才能把这种错误暴露成"握手即失败"。
 *
 * 实现手法刻意与云端不同（用 TreeMap 重建而非递归 set），就是为了避免写法上的巧合掩盖语义错误。
 */
public final class SimProtocol {

    public static final String VERSION = "1.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SimProtocol() {
    }

    /** 报文信封（字段语义见协议 §3）。 */
    public record Envelope(String v, String msgId, Long issuedAt, Long expireAt, String nonce, String traceId,
                           String sessionId, String from, String via, Long seq, String cmd, String code,
                           JsonNode data, String sign) {
    }

    public static String connectionPassword(String masterSecret, String username) {
        return hmac(derive(masterSecret, "conn"), username);
    }

    public static String messageSecret(String masterSecret) {
        return derive(masterSecret, "msg");
    }

    public static String derive(String masterSecret, String purpose) {
        return hmac(masterSecret, purpose);
    }

    public static String sign(String msgSecret, Envelope envelope) {
        return hmac(msgSecret, signBase(envelope));
    }

    public static boolean verify(String msgSecret, Envelope envelope) {
        return envelope.sign() != null && sign(msgSecret, envelope).equalsIgnoreCase(envelope.sign());
    }

    /** 签名基串：6 段换行分隔，缺失字段以空串参与（防"省略字段"绕过）。 */
    public static String signBase(Envelope envelope) {
        return safe(envelope.cmd()) + "\n" + safe(envelope.msgId()) + "\n"
                + (envelope.issuedAt() == null ? "" : envelope.issuedAt()) + "\n"
                + (envelope.expireAt() == null ? "" : envelope.expireAt()) + "\n"
                + safe(envelope.nonce()) + "\n"
                + hex(sha256(canonicalBytes(envelope.data())));
    }

    public static byte[] encode(Envelope envelope) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("v", envelope.v());
        node.put("msgId", envelope.msgId());
        put(node, "issuedAt", envelope.issuedAt());
        put(node, "expireAt", envelope.expireAt());
        put(node, "nonce", envelope.nonce());
        put(node, "traceId", envelope.traceId());
        put(node, "sessionId", envelope.sessionId());
        put(node, "from", envelope.from());
        put(node, "via", envelope.via());
        put(node, "seq", envelope.seq());
        put(node, "cmd", envelope.cmd());
        put(node, "code", envelope.code());
        node.set("data", envelope.data() == null ? MAPPER.createObjectNode() : envelope.data());
        put(node, "sign", envelope.sign());
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException("信封编码失败", e);
        }
    }

    public static Envelope decode(byte[] payload) {
        try {
            JsonNode root = MAPPER.readTree(new String(payload, StandardCharsets.UTF_8));
            return new Envelope(text(root, "v"), text(root, "msgId"), longVal(root, "issuedAt"),
                    longVal(root, "expireAt"), text(root, "nonce"), text(root, "traceId"),
                    text(root, "sessionId"), text(root, "from"), text(root, "via"), longVal(root, "seq"),
                    text(root, "cmd"), text(root, "code"), root.path("data"), text(root, "sign"));
        } catch (Exception e) {
            throw new IllegalArgumentException("报文解析失败：" + e.getMessage(), e);
        }
    }

    /** 递归按 key 排序后序列化，作为签名摘要的输入。 */
    public static byte[] canonicalBytes(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(sortedCopy(node));
        } catch (Exception e) {
            throw new IllegalStateException("载荷规范化失败", e);
        }
    }

    public static String canonicalText(JsonNode node) {
        return new String(canonicalBytes(node), StandardCharsets.UTF_8);
    }

    private static JsonNode sortedCopy(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return MAPPER.nullNode();
        }
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), sortedCopy(entry.getValue())));
            ObjectNode out = MAPPER.createObjectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            List<JsonNode> children = new ArrayList<>();
            node.forEach(child -> children.add(sortedCopy(child)));
            ArrayNode out = MAPPER.createArrayNode();
            children.forEach(out::add);
            return out;
        }
        return node;
    }

    public static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void put(ObjectNode node, String field, Object value) {
        if (value != null) {
            if (value instanceof Number number) {
                node.put(field, number.longValue());
            } else {
                node.put(field, String.valueOf(value));
            }
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long longVal(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isNumber() ? value.asLong() : Long.valueOf(value.asText());
    }
}
