package com.lrs.buddy.framework.iot.transport;

import java.util.Locale;

/**
 * MQTT 主题规范（swap-protocol.md §2.3）与类型化解析。
 *
 * <p>主题即协议边界：解析集中在此，业务侧只拿到类型化对象。
 * 散落的 startsWith 会让"改主题前缀"变成一次全局代码搜索。
 */
public final class MqttTopics {

    /** 协议主版本，进主题一段，滚动升级期允许相邻版本共存。 */
    public static final String VERSION = "v1";

    private static final String PREFIX = "swap/" + VERSION;

    private MqttTopics() {
    }

    public enum Kind {
        STATUS, TELEMETRY, EVENT, CMD_REPLY, UNKNOWN;

        public static Kind of(String segment) {
            return switch (segment == null ? "" : segment.toLowerCase(Locale.ROOT)) {
                case "status" -> STATUS;
                case "telemetry" -> TELEMETRY;
                case "event" -> EVENT;
                case "cmd_reply" -> CMD_REPLY;
                default -> UNKNOWN;
            };
        }
    }

    /**
     * 上行主题解析结果。
     *
     * @param productKey  品类
     * @param deviceId    直连设备标识
     * @param kind        语义类别
     * @param subDeviceId 子设备（电池透传）标识，非子设备为 null
     */
    public record Inbound(String productKey, String deviceId, Kind kind, String subDeviceId) {

        public boolean isSubDevice() {
            return subDeviceId != null;
        }
    }

    /**
     * 解析 up/{productKey}/{deviceId}/{kind} 与 up/{pk}/{dn}/sub/{subDeviceId}/{kind}。
     * 不匹配返回 null，调用方必须落 raw + 拒绝码，禁止静默丢弃。
     */
    public static Inbound parseInbound(String topic) {
        if (topic == null) {
            return null;
        }
        String[] seg = topic.split("/");
        if (seg.length < 2 || !"up".equals(seg[0])) {
            return null;
        }
        if (seg.length == 4) {
            return new Inbound(seg[1], seg[2], Kind.of(seg[3]), null);
        }
        if (seg.length == 6 && "sub".equals(seg[3])) {
            return new Inbound(seg[1], seg[2], Kind.of(seg[5]), seg[4]);
        }
        return null;
    }

    /** 云侧下行指令主题。 */
    public static String downCommand(String productKey, String deviceId) {
        return PREFIX + "/dn/" + productKey + "/" + deviceId + "/cmd";
    }

    /** 紧急控制独立主题：与常规队列隔离，防常规积压阻塞安全动作（协议 §4.3）。 */
    public static String downCritical(String productKey, String deviceId) {
        return downCommand(productKey, deviceId) + "/critical";
    }

    /** 影子同步主题。 */
    public static String downShadow(String productKey, String deviceId) {
        return PREFIX + "/dn/" + productKey + "/" + deviceId + "/shadow";
    }

    /** 云侧订阅全部上行的过滤器（生产可换 $share 共享订阅，由配置覆盖）。 */
    public static String upSubscribeFilter() {
        return PREFIX + "/up/#";
    }

    /** 通配符匹配（MQTT 语义：+ 单层、# 多层尾部），供 ACL 与测试使用。 */
    public static boolean matches(String topicFilter, String topic) {
        String[] f = topicFilter.split("/", -1);
        String[] t = topic.split("/", -1);
        for (int i = 0; i < f.length; i++) {
            if ("#".equals(f[i])) {
                return i == f.length - 1;
            }
            if (i >= t.length) {
                return false;
            }
            if (!"+".equals(f[i]) && !f[i].equals(t[i])) {
                return false;
            }
        }
        return f.length == t.length;
    }
}
