package com.lrs.buddy.framework.iot.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物模型校验器（协议 §11.4）。
 *
 * 三条实现立场：
 * 1 校验不通过的报文**必须连同原文落到 iot_raw_payload**。丢掉它就等于"数据不合我意就不许存在"，
 *   固件回滚、型号演进、设备厂商改字段时全部无从追溯。
 * 2 未定义的字段**不报错**（前向兼容：设备可以先于云端升级，多带字段是常态）。
 * 3 数值越界只判定、不修正。把 55℃ 悄悄夹到 45℃ 会让告警永远不触发，是最危险的"善意修正"。
 */
@Slf4j
@RequiredArgsConstructor
public class ThingModelValidator {

    private final DeviceDirectoryDao deviceDao;
    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, PropertySpec>> cache = new ConcurrentHashMap<>();

    public record Violation(String field, String reason, String detail) {
    }

    private record PropertySpec(String type, BigDecimal min, BigDecimal max, List<String> allowed) {
    }

    /** @return 违规列表；空表示合规 */
    public List<Violation> validate(String productKey, JsonNode data) {
        Map<String, PropertySpec> specs = specsOf(productKey);
        if (specs.isEmpty() || data == null || !data.isObject()) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        data.fields().forEachRemaining(entry -> {
            PropertySpec spec = specs.get(entry.getKey());
            if (spec == null) {
                return;
            }
            JsonNode value = entry.getValue();
            if ("decimal".equals(spec.type()) || "int".equals(spec.type())) {
                if (!value.isNumber()) {
                    violations.add(new Violation(entry.getKey(), "UNKNOWN_FIELD_TYPE", "期望数值"));
                    return;
                }
                BigDecimal num = value.decimalValue();
                if (spec.min() != null && num.compareTo(spec.min()) < 0
                        || spec.max() != null && num.compareTo(spec.max()) > 0) {
                    violations.add(new Violation(entry.getKey(), "OUT_OF_RANGE", num + " 不在 "
                            + spec.min() + "~" + spec.max()));
                }
            }
            if (spec.allowed() != null && !spec.allowed().isEmpty()
                    && !spec.allowed().contains(value.asText())) {
                violations.add(new Violation(entry.getKey(), "SCHEMA_VIOLATION", "枚举值非法"));
            }
        });
        return violations;
    }

    /** 型号未定义物模型时不做校验（宁可放行，也不因缺配置阻断主链路）。 */
    public boolean isModelled(String productKey) {
        return !specsOf(productKey).isEmpty();
    }

    private Map<String, PropertySpec> specsOf(String productKey) {
        return cache.computeIfAbsent(productKey, key -> {
            String specJson = deviceDao.findThingModelSpec(key);
            if (specJson == null) {
                return Map.of();
            }
            try {
                JsonNode spec = objectMapper.readTree(specJson);
                JsonNode properties = spec.path("properties");
                Map<String, PropertySpec> parsed = new java.util.HashMap<>();
                properties.fields().forEachRemaining(field -> {
                    JsonNode node = field.getValue();
                    List<String> allowed = new ArrayList<>();
                    node.path("enum").forEach(item -> allowed.add(item.asText()));
                    parsed.put(field.getKey(), new PropertySpec(node.path("type").asText("string"),
                            decimalOrNull(node.path("min")), decimalOrNull(node.path("max")), allowed));
                });
                return Map.copyOf(parsed);
            } catch (Exception e) {
                log.warn("物模型解析失败，跳过校验：productKey={}, err={}", key, e.getMessage());
                return Map.of();
            }
        });
    }

    public void evict(String productKey) {
        cache.remove(productKey);
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || !node.isNumber() ? null : node.decimalValue();
    }
}
