package com.lrs.buddy.framework.iot.session;

import io.vertx.mqtt.MqttEndpoint;
import io.netty.handler.codec.mqtt.MqttQoS;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已连接端点注册表：设备 → 本地 MqttEndpoint + Redis 上的"连在哪台节点"。
 *
 * 两层设计的必要性（swap-protocol.md §7.5）：
 * 柜机的长连接只落在某一台接入节点上，而下单请求可能落在任意业务实例。
 * 当前单体部署时 Redis 记录会退化为本节点自证，但这层抽象不能省 ——
 * 否则将来横向扩容时"指令找不到路"是无解的问题（要改的是一切调用点）。
 *
 * 端点必须显式 remove：只靠 sessionId 比对来判定"旧端点已失效"是不够的，
 * 泄漏的 MqttEndpoint 引用会随重连次数无上限增长。
 */
@Slf4j
public class EndpointRegistry {

    private static final String ROUTE_KEY_PREFIX = "buddy:iot:route:";

    private record Entry(MqttEndpoint endpoint, String sessionId) {
    }

    private final Map<Long, Entry> local = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final String nodeId;
    private final Duration routeTtl;

    public EndpointRegistry(StringRedisTemplate redis, String nodeId, int heartbeatSeconds) {
        this.redis = redis;
        this.nodeId = nodeId;
        // 路由记录 TTL 取 2 倍心跳：短于它会让正常在线的设备被误判为"不在本节点"
        this.routeTtl = Duration.ofSeconds(Math.max(60L, heartbeatSeconds * 2L));
    }

    public void register(Long deviceRowId, MqttEndpoint endpoint, String sessionId) {
        local.put(deviceRowId, new Entry(endpoint, sessionId));
        writeRoute(deviceRowId, sessionId);
    }

    public void unregister(Long deviceRowId, String sessionId) {
        local.computeIfPresent(deviceRowId, (id, entry) ->
                // 只摘掉本次会话对应的端点：重连后旧连接的关闭回调晚到，
                // 若无条件 remove 会把新连接的注册信息一起清掉，导致新会话收不到指令
                entry.sessionId().equals(sessionId) ? null : entry);
        if (local.get(deviceRowId) == null) {
            deleteRoute(deviceRowId, sessionId);
        }
    }

    /** 收到任何上行报文时续期路由记录，让"在线"与"可达"保持同一事实来源。 */
    public void touch(Long deviceRowId, String sessionId) {
        if (local.containsKey(deviceRowId)) {
            writeRoute(deviceRowId, sessionId);
        }
    }

    public String sessionIdOf(Long deviceRowId) {
        Entry entry = local.get(deviceRowId);
        return entry == null ? null : entry.sessionId();
    }

    public boolean isLocalConnected(Long deviceRowId) {
        Entry entry = local.get(deviceRowId);
        return entry != null && entry.endpoint().isConnected();
    }

    /** 向已连接设备发布下行消息（嵌入式 Broker 模式）。 */
    public boolean publish(Long deviceRowId, String topic, byte[] payload, int qos, boolean critical) {
        Entry entry = local.get(deviceRowId);
        if (entry == null || !entry.endpoint().isConnected()) {
            return false;
        }
        // 参数顺序为 (topic, payload, qos, retain, dup)；critical 体现在主题隔离上而非 QoS 参数
        entry.endpoint().publish(topic, io.vertx.core.buffer.Buffer.buffer(payload),
                MqttQoS.valueOf(qos), false, false);
        return true;
    }

    private void writeRoute(Long deviceRowId, String sessionId) {
        try {
            redis.opsForValue().set(ROUTE_KEY_PREFIX + deviceRowId, nodeId + "|" + sessionId, routeTtl);
        } catch (RuntimeException e) {
            log.warn("路由记录写入失败（不影响本节点直发）：{}", e.getMessage());
        }
    }

    private void deleteRoute(Long deviceRowId, String sessionId) {
        try {
            String key = ROUTE_KEY_PREFIX + deviceRowId;
            String current = redis.opsForValue().get(key);
            if (current != null && current.endsWith("|" + sessionId)) {
                redis.delete(key);
            }
        } catch (RuntimeException e) {
            log.debug("路由记录清理失败：{}", e.getMessage());
        }
    }

    public int localCount() {
        return local.size();
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
