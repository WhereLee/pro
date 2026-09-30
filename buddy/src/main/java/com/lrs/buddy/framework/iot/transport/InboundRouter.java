package com.lrs.buddy.framework.iot.transport;

import com.lrs.buddy.framework.iot.envelope.Envelope;
import com.lrs.buddy.framework.iot.envelope.JsonPayloadCodec;
import com.lrs.buddy.framework.iot.error.IotErrorCode;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao.Device;
import com.lrs.buddy.framework.iot.repo.IngestDao;
import com.lrs.buddy.framework.iot.security.DeviceCredentialService;
import com.lrs.buddy.framework.iot.security.DeviceSecrets;
import com.lrs.buddy.framework.iot.session.DeviceSessionService;
import com.lrs.buddy.framework.iot.session.EndpointRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 上行报文处理管道：协议 §6 的十步校验链在这里执行。
 *
 * 三条纪律：
 * 1 接收线程只做解析与投递，数据库访问都发生在 ingest 线程池（D3）——
 *   否则一条慢 SQL 会阻塞同一 event loop 上所有设备；
 * 2 顺序不能调：验签必须早于有效期判定，否则会拿一个可被伪造的 expireAt 做判断；
 * 3 每一步失败都写留痕并给出 reject_step，让"设备说发了、云端说没收"可判定。
 */
@Slf4j
public class InboundRouter {

    /** 业务侧监听器：只有校验链全通且首次出现的报文才会回调。 */
    public interface InboundListener {

        boolean supports(MqttTopics.Kind kind);

        void onInbound(MqttTopics.Inbound topic, Envelope envelope, Device device);
    }

    private final DeviceDirectoryDao deviceDao;
    private final DeviceCredentialService credentials;
    private final IngestDao ingestDao;
    private final JsonPayloadCodec codec;
    private final DeviceSecrets secrets;
    private final DedupService dedup;
    private final Executor ingestExecutor;
    private final List<InboundListener> listeners;
    private final DeviceSessionService sessions;
    private final EndpointRegistry endpoints;
    private final MeterRegistry registry;

    public InboundRouter(DeviceDirectoryDao deviceDao, DeviceCredentialService credentials, IngestDao ingestDao,
                         JsonPayloadCodec codec, DeviceSecrets secrets, DedupService dedup,
                         Executor ingestExecutor, List<InboundListener> listeners,
                         DeviceSessionService sessions, EndpointRegistry endpoints, MeterRegistry registry) {
        this.deviceDao = deviceDao;
        this.credentials = credentials;
        this.ingestDao = ingestDao;
        this.codec = codec;
        this.secrets = secrets;
        this.dedup = dedup;
        this.ingestExecutor = ingestExecutor;
        this.listeners = listeners;
        this.sessions = sessions;
        this.endpoints = endpoints;
        this.registry = registry;
    }

    /** 越权发布：计数 + 日志，不落库（不落库本身就是防写放大的设计）。 */
    public void rejected(Object endpoint, String topic) {
        registry.counter("security.reject.total", "code", "E1004").increment();
    }

    /** 收到任何合法上行都算一次心跳证据（在线判定的权威来源）。 */
    public void heartbeat(DeviceDirectoryDao.Device device) {
        sessions.markSeen(device);
    }

    /** 由 Broker 客户端回调入口调用；本方法立即返回，不阻塞网络线程。 */
    public void dispatch(String topicName, byte[] payload, String clientId, int qos) {
        ingestExecutor.execute(() -> handle(topicName, payload, clientId, qos));
    }

    void handle(String topicName, byte[] payload, String clientId, int qos) {
        LocalDateTime now = LocalDateTime.now();
        long ts = System.currentTimeMillis();
        MqttTopics.Inbound inbound = MqttTopics.parseInbound(topicName);
        Device device = resolveDevice(clientId);

        if (inbound == null || inbound.kind() == MqttTopics.Kind.UNKNOWN) {
            record(clientId, device, topicName, payload, false, IotErrorCode.E0003, now, ts);
            return;
        }
        if (device == null) {
            record(clientId, null, topicName, payload, false, IotErrorCode.E1004, now, ts);
            return;
        }

        Envelope envelope;
        try {
            envelope = codec.decode(payload);
        } catch (JsonPayloadCodec.MalformedPayloadException e) {
            record(clientId, device, topicName, payload, false, IotErrorCode.E0001, now, ts);
            raw(device, topicName, payload, "MALFORMED", e.getMessage(), now);
            return;
        }
        if (envelope.v() == null || !envelope.v().startsWith("1.")) {
            record(clientId, device, topicName, payload, false, IotErrorCode.E0002, now, ts);
            return;
        }
        String msgSecret = credentials.messageSecretOf(device);
        if (msgSecret == null || !secrets.verify(msgSecret, envelope)) {
            record(clientId, device, topicName, payload, false, IotErrorCode.E1001, now, ts);
            return;
        }
        if (envelope.expiredAt(ts)) {
            record(clientId, device, topicName, payload, false, IotErrorCode.E1003, now, ts);
            return;
        }
        // 跨会话迟到报文丢弃：不丢弃就会出现"我扫了 B 单，A 单莫名完成、电池还少一块"
        if (envelope.sessionId() != null) {
            String active = deviceDao.activeSessionId(device.id());
            if (active != null && !active.equals(envelope.sessionId())) {
                record(clientId, device, topicName, payload, false, IotErrorCode.S_SESSION_STALE, now, ts);
                return;
            }
        }
        if (envelope.msgId() == null || envelope.msgId().isBlank()) {
            record(clientId, device, topicName, payload, false, IotErrorCode.E0001, now, ts);
            return;
        }
        if (!dedup.firstSeen(device.id(), envelope.msgId(), topicName, eventType(envelope), now)) {
            // 重复投递是正常结果：留痕但不再分发，否则重投会重复扣权益
            record(clientId, device, topicName, payload, true, IotErrorCode.OK, now, ts);
            log.debug("重复上行已按幂等丢弃：deviceId={}, msgId={}", device.deviceId(), envelope.msgId());
            return;
        }
        record(clientId, device, topicName, payload, true, IotErrorCode.OK, now, ts);
        sessions.markSeen(device);
        endpoints.touch(device.id(), envelope.sessionId() == null ? endpoints.sessionIdOf(device.id()) : envelope.sessionId());
        deliver(inbound, envelope, device);
    }

    private void deliver(MqttTopics.Inbound inbound, Envelope envelope, Device device) {
        for (InboundListener listener : listeners) {
            if (!listener.supports(inbound.kind())) {
                continue;
            }
            try {
                listener.onInbound(inbound, envelope, device);
            } catch (RuntimeException e) {
                // 单个监听器失败不得影响其他监听器，也不能让报文被误判为未收到
                log.error("上行监听器处理异常：listener={}, deviceId={}, cmd={}",
                        listener.getClass().getSimpleName(), device.deviceId(), envelope.cmd(), e);
            }
        }
    }

    private static String eventType(Envelope envelope) {
        String eventType = DeviceSecrets.dataText(envelope.data(), "eventType");
        return eventType != null ? eventType : envelope.cmd();
    }

    private Device resolveDevice(String clientId) {
        if (clientId == null || !clientId.contains("::") || MqttSecurityPolicies.isCloudClient(clientId)) {
            return null;
        }
        int idx = clientId.indexOf("::");
        return deviceDao.findDevice(clientId.substring(0, idx), clientId.substring(idx + 2));
    }

    private void record(String clientId, Device device, String topic, byte[] payload, boolean valid,
                        IotErrorCode code, LocalDateTime now, long ts) {
        String text = payload == null ? "" : new String(payload, StandardCharsets.UTF_8);
        ingestDao.insertMessageLog(nextId(), "UP",
                device == null ? productKeyOf(clientId) : device.productKey(),
                device == null ? null : device.id(), topic, null, null, null, null,
                truncate(text), valid, valid ? null : code.code(), code.step().name(),
                now, ts, device == null ? 1L : device.tenantId());
    }

    private void raw(Device device, String topic, byte[] payload, String reason, String detail, LocalDateTime now) {
        ingestDao.insertRawPayload(nextId(), device.id(), device.productKey(), null, topic,
                truncate(new String(payload == null ? new byte[0] : payload, StandardCharsets.UTF_8)),
                reason, detail, now, device.tenantId());
    }

    private static String productKeyOf(String clientId) {
        if (clientId == null || !clientId.contains("::")) {
            return null;
        }
        return clientId.substring(0, clientId.indexOf("::"));
    }

    /**
     * 留痕上限：超长按截断而不是拒收，
     * 否则一个异常大的报文会让"留痕失败"反过来打挂主链路。
     */
    private static String truncate(String text) {
        int limit = 2000;
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static long nextId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }
}
