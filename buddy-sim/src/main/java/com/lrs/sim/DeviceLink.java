package com.lrs.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.lrs.sim.device.CabinetDevice;
import com.lrs.sim.fault.FaultPolicy;
import com.lrs.sim.protocol.SimProtocol;
import com.lrs.sim.protocol.ValidationChain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一台虚拟柜机：连接、收指令、执行、上报事实。
 *
 * 事件与应答分主题、且**执行结果由事件证明**（协议 §4.1 铁律二：ACK 不等于动作发生）。
 * 因此这里先回 ACK（通信层事实），再单独发 door_open（物理事实）——
 * 两者顺序与是否被抑制，就是 FI-04 能存在的结构性原因。
 */
public class DeviceLink implements AutoCloseable {

    public static final List<String> KNOWN_COMMANDS = List.of("OPEN_SLOT", "UNLOCK_SLOT", "LOCK_SLOT",
            "QUERY_STATUS", "SET_PARAMS", "START_CHARGE", "STOP_CHARGE", "EMERGENCY_STOP",
            "BATTERY_VERIFY", "BUZZER", "REBOOT", "OTA_PUSH");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String host;
    private final int port;
    private final String productKey;
    private final String deviceId;
    private final String masterSecret;
    private final CabinetDevice cabinet;
    private final FaultPolicy faults;
    private final ValidationChain chain;
    private final AtomicLong seq = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();
    private final String from;

    private Mqtt5BlockingClient client;
    private volatile String sessionId = "s-" + UUID.randomUUID().toString().substring(0, 12);
    private volatile long lastTrustedNowMs = System.currentTimeMillis();

    public DeviceLink(String host, int port, String productKey, String deviceId, String masterSecret,
                      CabinetDevice cabinet, FaultPolicy faults) {
        this.host = host;
        this.port = port;
        this.productKey = productKey;
        this.deviceId = deviceId;
        this.masterSecret = masterSecret;
        this.cabinet = cabinet;
        this.faults = faults;
        this.from = productKey + "::" + deviceId;
        this.chain = new ValidationChain(SimProtocol.messageSecret(masterSecret), KNOWN_COMMANDS);
    }

    public String sessionId() {
        return sessionId;
    }

    public CabinetDevice cabinet() {
        return cabinet;
    }

    public void connect() {
        String username = deviceId + "|" + System.currentTimeMillis() + "|" + UUID.randomUUID();
        String password = SimProtocol.connectionPassword(masterSecret, username);
        client = MqttClient.builder().useMqttVersion5()
                .identifier(from)
                .serverHost(host).serverPort(port)
                .simpleAuth().username(username)
                .password(ByteBuffer.wrap(password.getBytes(StandardCharsets.UTF_8)))
                .applySimpleAuth().buildBlocking();
        client.connectWith().cleanStart(true).keepAlive(60).send();
        client.subscribeWith().topicFilter("swap/v1/dn/" + productKey + "/" + deviceId + "/#")
                .qos(MqttQos.AT_LEAST_ONCE).send();
        running.set(true);
        Thread consumer = new Thread(this::consumeLoop, "sim-" + deviceId + "-consumer");
        consumer.setDaemon(true);
        consumer.start();
    }

    /** 模拟设备断电重连：换新 sessionId 并重新订阅（FI-10 的前半段）。 */
    public void reconnect() {
        disconnect();
        sessionId = "s-" + UUID.randomUUID().toString().substring(0, 12);
        connect();
    }

    private void consumeLoop() {
        var publishes = client.publishes(MqttGlobalPublishFilter.ALL);
        while (running.get()) {
            try {
                Optional<Mqtt5Publish> next = publishes.receive(300, TimeUnit.MILLISECONDS);
                if (next.isPresent()) {
                    handleCommand(next.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                System.err.println("[sim:" + deviceId + "] 处理下行异常：" + e.getMessage());
            }
        }
    }

    private void handleCommand(Mqtt5Publish publish) {
        byte[] payload = publish.getPayloadAsBytes();
        boolean critical = publish.getTopic().toString().endsWith("/cmd/critical");
        lastTrustedNowMs = System.currentTimeMillis();
        ValidationChain.Outcome outcome = chain.accept(payload, lastTrustedNowMs, true,
                envelope -> execute(envelope, critical));
        String cmd = outcome.envelope() == null ? "" : String.valueOf(outcome.envelope().cmd());
        switch (outcome.verdict()) {
            case ACCEPT -> {
                // FI-01：不回任何应答。注意动作已经发生了（门已开），只是不应答 ——
                // 这正是"超时不等于未发生"要能测出来的形状。
                if (faults.swallowReply(cmd)) {
                    faults.recordFired("dropReply:" + cmd);
                    return;
                }
                replyWithRepeat(outcome.envelope(), cmd);
            }
            // 关键：重复指令不是"忽略"，而是重放上次应答；否则云侧拿不到回执会误判超时
            case DUPLICATE_REPLAYED -> publishEnvelope(outcome.envelope());
            default -> publishEnvelope(rejectReply(outcome, payload));
        }
    }

    /** FI-02：同一条应答可重复投递 N 次（含抖动），用于验证云侧幂等。 */
    private void replyWithRepeat(SimProtocol.Envelope reply, String cmd) {
        int times = Math.max(1, faults.replyRepeat(cmd));
        for (int i = 0; i < times; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(faults.jitterMillis(200));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                faults.recordFired("duplicateReply:" + cmd + "#" + i);
            }
            publishEnvelope(reply);
        }
    }

    /** 被拒的指令也要给一个明确错误码，不能静默吞掉。 */
    private SimProtocol.Envelope rejectReply(ValidationChain.Outcome outcome, byte[] payload) {
        String code = switch (outcome.verdict()) {
            case REJECT_SIGN -> "E1001";
            case REPLAY -> "E1002";
            case EXPIRED -> "E1003";
            case UNKNOWN_CMD -> "E0003";
            default -> "E9999";
        };
        ObjectNode data = MAPPER.createObjectNode().put("detail", outcome.verdict().name());
        if (outcome.msgId() != null) {
            data.put("cmdId", outcome.msgId());
        }
        long now = System.currentTimeMillis();
        SimProtocol.Envelope unsigned = new SimProtocol.Envelope(SimProtocol.VERSION, ulid(), now, now + 60_000L,
                ulid(), null, sessionId, from, null, seq.incrementAndGet(), null, code, data, null);
        String sign = SimProtocol.sign(SimProtocol.messageSecret(masterSecret), unsigned);
        return new SimProtocol.Envelope(SimProtocol.VERSION, unsigned.msgId(), now, unsigned.expireAt(),
                unsigned.nonce(), null, sessionId, from, null, unsigned.seq(), null, code, data, sign);
    }

    private void publishEnvelope(SimProtocol.Envelope envelope) {
        if (envelope == null) {
            return;
        }
        client.publishWith().topic("swap/v1/up/" + productKey + "/" + deviceId + "/cmd_reply")
                .qos(MqttQos.AT_LEAST_ONCE).payload(SimProtocol.encode(envelope)).send();
    }

    /** 执行并产出应答；物理事件由 executeXxx 内部按故障策略决定是否上报。 */
    private SimProtocol.Envelope execute(SimProtocol.Envelope envelope, boolean critical) {
        String cmd = envelope.cmd();
        ObjectNode replyData = MAPPER.createObjectNode();
        replyData.put("cmdId", envelope.msgId());
        String nack = faults.nackCode(cmd);
        int slotNo = envelope.data() == null ? 0 : envelope.data().path("slotNo").asInt(0);

        if (nack != null) {
            replyData.put("detail", "INJECTED_NACK");
            faults.recordFired("nack:" + cmd + "=" + nack);
            return reply(envelope, nack, replyData, critical);
        }
        switch (cmd == null ? "" : cmd) {
            case "OPEN_SLOT", "UNLOCK_SLOT" -> {
                if (!cabinet.openDoor(slotNo)) {
                    replyData.put("detail", "LOCK_JAM");
                    return reply(envelope, "E3001", replyData, critical);
                }
                publishDoorEvent(slotNo, "door_open", "CMD", !faults.suppressEvent(cmd));
            }
            case "LOCK_SLOT" -> {
                cabinet.closeDoor(slotNo);
                replyData.put("locked", true);
            }
            case "STOP_CHARGE", "EMERGENCY_STOP" -> {
                List<Integer> stopped = cabinet.stopAllCharging();
                replyData.put("stoppedSlots", MAPPER.valueToTree(stopped));
                if (critical) {
                    publishAlarm(stopped);
                }
            }
            case "QUERY_STATUS" -> replyData.set("slots", MAPPER.valueToTree(cabinet.slots().stream()
                    .map(slot -> slot.no + ":" + slot.door + ":" + slot.soc).toList()));
            case "BATTERY_VERIFY" -> {
                String challenge = envelope.data() == null ? "" : envelope.data().path("challenge").asText("");
                var battery = slotNo == 0 ? null : cabinet.slot(slotNo).batteryCode;
                replyData.put("batteryCode", battery == null ? "" : battery);
                replyData.put("verifyDigest", battery == null ? ""
                        : SimProtocol.hmac(SimProtocol.messageSecret(masterSecret), challenge + battery));
            }
            default -> {
                // 已知但无副作用动作（BUZZER 等）：直接确认
            }
        }
        return reply(envelope, "OK", replyData, critical);
    }

    /** 用户取走满电电池；FI-13 时不发 door_close。 */
    public void simulateUserTake(int slotNo, String batteryCode, int soc) {
        cabinet.insert(slotNo, batteryCode, soc, 30.0);
        CabinetDevice.Battery taken = cabinet.take(slotNo);
        publishEvent("battery_taken", MAPPER.createObjectNode()
                .put("slotNo", slotNo)
                .put("batteryCode", taken == null ? batteryCode : taken.code()));
        if (!faults.leaveDoorOpen("UNLOCK_SLOT")) {
            cabinet.closeDoor(slotNo);
            publishEvent("door_close", MAPPER.createObjectNode().put("slotNo", slotNo).put("magnet", "CLOSED"));
        } else {
            faults.recordFired("leaveDoorOpen:" + slotNo);
        }
    }

    /** 用户投入旧电池后关门（door_close 与 battery_detected 是两个事实）。 */
    public void simulateUserInsert(int slotNo, String batteryCode, int soc) {
        cabinet.insert(slotNo, batteryCode, soc, 30.0);
        publishEvent("battery_detected", MAPPER.createObjectNode()
                .put("slotNo", slotNo).put("batteryCode", batteryCode).put("soc", soc));
        cabinet.closeDoor(slotNo);
        publishEvent("door_close", MAPPER.createObjectNode().put("slotNo", slotNo).put("magnet", "CLOSED"));
    }

    public void publishTelemetry(ObjectNode props) {
        publish("swap/v1/up/" + productKey + "/" + deviceId + "/telemetry", props, false);
    }

    private void publishDoorEvent(int slotNo, String eventType, String trigger, boolean send) {
        if (!send) {
            faults.recordFired("suppressEvent:" + eventType + ":" + slotNo);
            return;
        }
        publishEvent(eventType, MAPPER.createObjectNode()
                .put("slotNo", slotNo).put("magnet", "OPEN".equals(eventType) ? "OPEN" : "CLOSED")
                .put("trigger", trigger));
    }

    private void publishAlarm(List<Integer> stopped) {
        publishEvent("alarm", MAPPER.createObjectNode()
                .put("code", "TEMP_HIGH").put("level", "CRITICAL")
                .put("cabinetTemp", cabinet.cabinetTemp())
                .put("stoppedSlots", stopped.size()));
    }

    private void publishEvent(String eventType, ObjectNode data) {
        data.put("eventType", eventType);
        publish("swap/v1/up/" + productKey + "/" + deviceId + "/event", data, false);
    }

    private SimProtocol.Envelope reply(SimProtocol.Envelope envelope, String code, ObjectNode data, boolean critical) {
        return build("swap/v1/up/" + productKey + "/" + deviceId + "/cmd_reply", envelope.cmd(), code, data,
                critical);
    }

    private SimProtocol.Envelope build(String topic, String cmd, String code, ObjectNode data, boolean critical) {
        long now = System.currentTimeMillis();
        SimProtocol.Envelope unsigned = new SimProtocol.Envelope(SimProtocol.VERSION, ulid(), now,
                now + 60_000L, UUID.randomUUID().toString().substring(0, 8), null, sessionId, from, null,
                seq.incrementAndGet(), cmd, code, data, null);
        String sign = SimProtocol.sign(SimProtocol.messageSecret(masterSecret), unsigned);
        return new SimProtocol.Envelope(SimProtocol.VERSION, unsigned.msgId(), now, unsigned.expireAt(),
                unsigned.nonce(), null, sessionId, from, null, unsigned.seq(), cmd, code, data, sign);
    }

    private void publish(String topic, ObjectNode data, boolean reply) {
        SimProtocol.Envelope envelope = build(topic, reply ? "REPLY" : null, reply ? "OK" : null, data, false);
        client.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE)
                .payload(SimProtocol.encode(envelope)).send();
    }

    private static String ulid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 26).toUpperCase();
    }

    private void disconnect() {
        running.set(false);
        try {
            if (client != null) {
                client.disconnectWith().send();
            }
        } catch (RuntimeException ignored) {
            // 重连场景下连接可能已经断了
        }
    }

    @Override
    public void close() {
        disconnect();
    }
}
