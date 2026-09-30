package com.lrs.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.lrs.sim.protocol.SimProtocol;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 场景编排用的**最小云侧替身**：往 dn 主题发一条签名指令，并把 up 主题收到的报文收下来供断言。
 *
 * 它不是 buddy 云侧的替代品（不参与验签之外的任何校验链、不写库、没有状态机），
 * 只解决一件事：**在同一个进程里既当对手方又当观测者**，让设备侧的 FI 用例不必 fork 一个真实后端。
 * 真正的跨进程联跑仍由 {@code scripts/cross-process-swap.sh} 用真 buddy 跑，两者职责不重叠。
 *
 * 签名用与设备侧同一套基串派生（{@link SimProtocol#messageSecret}）——这是协议规定的"云侧也按信封签名"，
 * 不是偷懒：如果这里换成另一套算法，FI-08（错签）就永远测不到设备侧的拒绝分支。
 */
public final class SimCloudDriver implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String host;
    private final int port;
    private final String productKey;
    private final String deviceId;
    private final String masterSecret;
    private final AtomicLong seq = new AtomicLong();

    private Mqtt5BlockingClient client;
    private final List<SimProtocol.Envelope> received = new CopyOnWriteArrayList<>();
    /** 按指令码记住"上一条真正发出去的报文"，FI-07 的重放才能原样再来一次。 */
    private final java.util.Map<String, SimProtocol.Envelope> lastSentByCmd = new java.util.concurrent.ConcurrentHashMap<>();

    public SimCloudDriver(String host, int port, String productKey, String deviceId, String masterSecret) {
        this.host = host;
        this.port = port;
        this.productKey = productKey;
        this.deviceId = deviceId;
        this.masterSecret = masterSecret;
    }

    public void connect() {
        client = MqttClient.builder().useMqttVersion5().identifier("sim-cloud-" + UUID.randomUUID())
                .serverHost(host).serverPort(port).buildBlocking();
        client.connectWith().cleanStart(true).send();
        client.subscribeWith().topicFilter("swap/v1/up/" + productKey + "/" + deviceId + "/#")
                .qos(MqttQos.AT_LEAST_ONCE).send();
        var publishes = client.publishes(MqttGlobalPublishFilter.ALL);
        Thread pump = new Thread(() -> {
            while (true) {
                try {
                    Optional<Mqtt5Publish> next = publishes.receive(200, TimeUnit.MILLISECONDS);
                    next.ifPresent(p -> received.add(SimProtocol.decode(p.getPayloadAsBytes())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    // 解码失败也要留痕：上行出现一条解不开的报文本身就是缺陷，不能被线程退出吞掉
                    received.add(null);
                }
            }
        }, "sim-cloud-pump-" + deviceId);
        pump.setDaemon(true);
        pump.start();
    }

    /** 发一条普通指令（走 cmd 主题）。 */
    public String sendCommand(String cmd, ObjectNode data, int ttlSeconds) {
        return sendCommand(cmd, data, ttlSeconds, false);
    }

    /** 发一条指令；critical=true 时走 cmd/critical（协议 §4.3 的紧急通道）。 */
    public String sendCommand(String cmd, ObjectNode data, int ttlSeconds, boolean critical) {
        String msgId = ulid();
        long now = System.currentTimeMillis();
        SimProtocol.Envelope unsigned = new SimProtocol.Envelope(SimProtocol.VERSION, msgId, now,
                now + ttlSeconds * 1000L, UUID.randomUUID().toString().substring(0, 8), null, null,
                "cloud::sim-driver", null, seq.incrementAndGet(), cmd, null, data, null);
        String sign = SimProtocol.sign(SimProtocol.messageSecret(masterSecret), unsigned);
        SimProtocol.Envelope envelope = new SimProtocol.Envelope(unsigned.v(), unsigned.msgId(), unsigned.issuedAt(),
                unsigned.expireAt(), unsigned.nonce(), null, unsigned.sessionId(), unsigned.from(), unsigned.via(),
                unsigned.seq(), unsigned.cmd(), unsigned.code(), unsigned.data(), sign);
        String topic = "swap/v1/dn/" + productKey + "/" + deviceId + "/cmd" + (critical ? "/critical" : "");
        client.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE)
                .payload(SimProtocol.encode(envelope)).send();
        lastSentByCmd.put(cmd, envelope);
        return msgId;
    }

    /**
     * 发一条**发出时已过期**的指令（FI-06）：设备必须回 {@code E1003} 并且不执行。
     *
     * 为什么单独一个方法而不是让调用方传时间戳：过期报文的关键在"签名时就是过期的"，
     * 手写两个时间戳很容易拼成"签名与内容不一致"，那就测到的是 FI-08 而不是 FI-06。
     */
    public String sendExpired(String cmd, ObjectNode data, int expiredSeconds) {
        long now = System.currentTimeMillis();
        return sendRaw(cmd, data, now - (expiredSeconds + 60) * 1000L,
                now - expiredSeconds * 1000L, SimProtocol.messageSecret(masterSecret));
    }

    /** 发一条**用错密钥签**的指令（FI-08）：设备必须回 {@code E1001} 并且不执行。 */
    public String sendBadSign(String cmd, ObjectNode data) {
        long now = System.currentTimeMillis();
        return sendRaw(cmd, data, now, now + 60_000L,
                SimProtocol.messageSecret("wrong-" + masterSecret));
    }

    /**
     * 发一条**复用旧 nonce、msgId 是新的**指令（FI-07）：设备必须回 E1002。
     *
     * 为什么不是"原样重发"：原样重发的 msgId 也相同，设备侧会当成 QoS1 重投而**重放上次应答**
     * （协议 §6 的幂等优先于 nonce），那是 FI-02 的形状。真重放的定义是"签名与 nonce 旧、消息号新"，
     * 两者必须分两条用例，否则"重放防护"可以靠幂等重放假装实现。
     */
    public String sendReusingNonce(String cmd, ObjectNode data) {
        SimProtocol.Envelope previous = lastSentByCmd.get(cmd);
        if (previous == null) {
            throw new IllegalStateException("没有可复用的 nonce：cmd=" + cmd + "（必须先 send 同一条指令）");
        }
        long now = System.currentTimeMillis();
        SimProtocol.Envelope unsigned = new SimProtocol.Envelope(SimProtocol.VERSION, ulid(), now, now + 60_000L,
                previous.nonce(), null, null, "cloud::sim-driver", null, seq.incrementAndGet(), cmd, null, data, null);
        String sign = SimProtocol.sign(SimProtocol.messageSecret(masterSecret), unsigned);
        SimProtocol.Envelope envelope = new SimProtocol.Envelope(unsigned.v(), unsigned.msgId(), unsigned.issuedAt(),
                unsigned.expireAt(), unsigned.nonce(), null, unsigned.sessionId(), unsigned.from(), unsigned.via(),
                unsigned.seq(), unsigned.cmd(), unsigned.code(), unsigned.data(), sign);
        client.publishWith().topic("swap/v1/dn/" + productKey + "/" + deviceId + "/cmd")
                .qos(MqttQos.AT_LEAST_ONCE).payload(SimProtocol.encode(envelope)).send();
        return envelope.msgId();
    }

    /** 原样重发同一条报文（FI-02 形状：同 msgId 的重投，设备应重放上次应答而不重复执行）。 */
    public void resend(String cmd) {
        SimProtocol.Envelope envelope = lastSentByCmd.get(cmd);
        if (envelope == null) {
            throw new IllegalStateException("没有可重放的指令：cmd=" + cmd + "（必须先 send 同一条指令）");
        }
        resend(envelope);
    }

    public void resend(SimProtocol.Envelope envelope) {
        client.publishWith().topic("swap/v1/dn/" + productKey + "/" + deviceId + "/cmd")
                .qos(MqttQos.AT_LEAST_ONCE).payload(SimProtocol.encode(envelope)).send();
    }

    /** 自己拼一条报文发出去（过期、错签、篡改三种异常报文都由调用方写明，驱动侧不做默认值）。 */
    public String sendRaw(String cmd, ObjectNode data, long issuedAt, long expireAt, String secret) {
        String msgId = ulid();
        SimProtocol.Envelope unsigned = new SimProtocol.Envelope(SimProtocol.VERSION, msgId, issuedAt, expireAt,
                UUID.randomUUID().toString().substring(0, 8), null, null, "cloud::sim-driver", null,
                seq.incrementAndGet(), cmd, null, data, null);
        String sign = SimProtocol.sign(secret, unsigned);
        SimProtocol.Envelope envelope = new SimProtocol.Envelope(unsigned.v(), unsigned.msgId(), unsigned.issuedAt(),
                unsigned.expireAt(), unsigned.nonce(), null, unsigned.sessionId(), unsigned.from(), unsigned.via(),
                unsigned.seq(), unsigned.cmd(), unsigned.code(), unsigned.data(), sign);
        client.publishWith().topic("swap/v1/dn/" + productKey + "/" + deviceId + "/cmd")
                .qos(MqttQos.AT_LEAST_ONCE).payload(SimProtocol.encode(envelope)).send();
        return msgId;
    }

    /** 等某类报文出现；返回是否出现（超时不抛异常，让断言自己决定怎么报）。 */
    public boolean awaitEvent(String eventType, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (eventsOfType(eventType).isEmpty()) {
                sleep(50);
            } else {
                return true;
            }
        }
        return !eventsOfType(eventType).isEmpty();
    }

    public boolean awaitReply(String cmdCode, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (replies(cmdCode).isEmpty()) {
                sleep(50);
            } else {
                return true;
            }
        }
        return !replies(cmdCode).isEmpty();
    }

    public List<SimProtocol.Envelope> replies(String cmdCode) {
        return received.stream().filter(e -> e != null && e.cmd() != null && e.cmd().equals(cmdCode)
                && e.code() != null).toList();
    }

    /**
     * 所有带 code 的上行（不分指令码）。
     *
     * 为什么需要它：设备拒绝一条指令时回的是**错误码报文，里面没有 cmd**（它不肯相信一个
     * 验不过签的报文里的 cmd），所以"E1001/E1002/E1003 有没有回来"只能按 code 数，不能按 cmd 数。
     */
    public List<SimProtocol.Envelope> codedReplies() {
        return received.stream().filter(e -> e != null && e.code() != null).toList();
    }

    public boolean hasReplyCode(String code) {
        return codedReplies().stream().anyMatch(e -> code.equals(e.code()));
    }

    public List<SimProtocol.Envelope> eventsOfType(String eventType) {
        return received.stream()
                .filter(e -> e != null && e.data() != null && eventType.equals(e.data().path("eventType").asText(null)))
                .toList();
    }

    /** 上行里报文的到达顺序（FI-05 断言"倒挂是否真的发生"，以及云侧是否被它带跑）。 */
    public List<String> arrivalOrder() {
        return received.stream().map(e -> e == null ? "<undecodable>"
                        : (e.data() != null && e.data().has("eventType")
                        ? e.data().path("eventType").asText() : "reply:" + e.code())).toList();
    }

    public List<SimProtocol.Envelope> received() {
        return List.copyOf(received);
    }

    /** 清空已收到的报文：一条故障场景只断言自己这一段，不让上一条用例的残留影响计数。 */
    public void clearReceived() {
        received.clear();
    }

    public static ObjectNode data(String field, int value) {
        return MAPPER.createObjectNode().put(field, value);
    }

    private static String ulid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 26).toUpperCase();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (client != null) {
            try {
                client.disconnectWith().send();
            } catch (RuntimeException ignored) {
                // 已经在断开的连接上再断一次不是错误
            }
        }
    }
}
