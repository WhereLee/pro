package com.lrs.buddy.framework.iot.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/**
 * JSON 报文编解码（M1 唯一实现）。
 *
 * 两点刻意的行为：
 * 1 反序列化忽略未知字段 —— 协议 §12 的前向兼容要求，现场设备无法与云端同步升级；
 * 2 但未知 cmd 不在此处理，必须回 E0003 而不是静默忽略 —— 静默会让云侧误判"设备接受但未响应"。
 */
public class JsonPayloadCodec implements PayloadCodec {

    public static final String NAME = "json-v1";

    private final ObjectMapper mapper;
    private final ObjectWriter writer;

    public JsonPayloadCodec() {
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.writer = mapper.copy()
                // 设备侧实现要能逐字段复现字节，排序便于跨端比对与签名规范化
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writer();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public byte[] encode(Envelope envelope) {
        try {
            return writer.writeValueAsBytes(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("信封编码失败", e);
        }
    }

    @Override
    public Envelope decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new MalformedPayloadException("空报文", 0, null);
        }
        try {
            JsonNode root = mapper.readTree(new String(payload, StandardCharsets.UTF_8));
            if (!root.isObject()) {
                throw new MalformedPayloadException("报文根节点非对象", payload.length, null);
            }
            return new Envelope(
                    text(root, "v"),
                    text(root, "msgId"),
                    longVal(root, "issuedAt"),
                    longVal(root, "expireAt"),
                    text(root, "nonce"),
                    text(root, "traceId"),
                    text(root, "sessionId"),
                    text(root, "from"),
                    text(root, "via"),
                    longVal(root, "seq"),
                    text(root, "cmd"),
                    text(root, "code"),
                    root.path("data"),
                    text(root, "sign"));
        } catch (MalformedPayloadException e) {
            throw e;
        } catch (Exception e) {
            throw new MalformedPayloadException("报文解析失败", payload.length, e);
        }
    }

    /**
     * 规范化 data 子树的字节表示，供签名使用。
     *
     * 刻意自己递归重建并排序，而不依赖 ORDER_MAP_ENTRIES_BY_KEYS：
     * 不规范化会出现"同一语义 JSON 因 key 顺序不同导致验签失败"这类不可复现缺陷，
     * 而把正确性押在某个 mapper 配置项上，等于埋一颗"改配置即断签名"的雷。
     */
    public byte[] canonicalData(JsonNode data) {
        try {
            return mapper.writeValueAsBytes(sortedCopy(data));
        } catch (Exception e) {
            throw new IllegalStateException("载荷规范化失败", e);
        }
    }

    private JsonNode sortedCopy(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return mapperNull();
        }
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            java.util.List<String> names = new java.util.ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(java.util.Comparator.naturalOrder());
            for (String name : names) {
                out.set(name, sortedCopy(node.get(name)));
            }
            return out;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode out = mapper.createArrayNode();
            node.forEach(child -> out.add(sortedCopy(child)));
            return out;
        }
        return node;
    }

    private JsonNode mapperNull() {
        return com.fasterxml.jackson.databind.node.NullNode.getInstance();
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static Long longVal(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isNumber() && !n.isTextual()) {
            return null;
        }
        return n.isNumber() ? n.asLong() : Long.valueOf(n.asText());
    }
}
