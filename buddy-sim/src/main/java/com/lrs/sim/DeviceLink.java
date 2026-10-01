package com.lrs.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient.Mqtt5Publishes;
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

    /**
     * 下行发布流。必须在 {@link #connect()} 内同步注册好：
     * 若留到消费线程里再注册，connect() 返回后、线程尚未注册前到达的指令**会被丢弃**
     * （HiveMQ 的 publishes() 不回溯投递）。本机因为线程抢在前面而全绿，
     * CI 上则固定表现为“第一条下行指令 received=0”——这是设备侧实现缺陷，不是测试替身的问题。
     */
    private volatile Mqtt5Publishes publishes;
    private final String from;

    /**
     * 设备侧计数（swap-simulator.md §8 双端可归因）。
     *
     * 没有设备侧计数器，“云侧说没收到”与“设备根本没发”就分不开，
     * 而压测结论里“不变式违反数 = 0”也会变成只是云侧自说了。不引 Micrometer：
     * 模拟器只要五个数，手写 exposition 比为了它拉一套注册器更诚实（见 {@code SimControlServer#metrics()}）。
     */
    private final AtomicLong commandsReceived = new AtomicLong();
    private final AtomicLong repliesSent = new AtomicLong();
    private final AtomicLong eventsSent = new AtomicLong();
    private final AtomicLong telemetrySent = new AtomicLong();
    private final AtomicLong rejectedInbound = new AtomicLong();

    private Mqtt5BlockingClient client;
    private volatile String sessionId = "s-" + UUID.randomUUID().toString().substring(0, 12);
    /** FI-10：重连后的"上一个会话号"。没这个字段就伪造不出旧会话应答，而云侧判迟到只能比对会话号。 */
    private volatile String previousSessionId;
    private volatile long lastTrustedNowMs = System.currentTimeMillis();

    /** 自动跑完一次换电（跨进程联跑用）：门开后自动代用户投入/取走。 */
    private volatile boolean autoSwap;
    private volatile String oldBatteryCode = "BAT-USER-1";
    private volatile int oldBatterySoc = 30;
    private volatile String offerBatteryCode;
    private volatile long autoSwapDelayMs = 1500L;

    /**
     * 开启自动换电动作。
     *
     * 为什么用 setter 而不是改构造器：现有测试与 CLI 都在用 7 参构造，为一个可选行为
     * 把必填参数拉到 11 个只会让调用方四处改。电池编码由脚本传入，
     * 因为 `battery_taken` 必须带与云侧分配一致的编码——否则会被云侧判 IDENTITY_SUSPECT，
     * 那不是联跑失败，而是身份校验正常工作。
     */
    public void enableAutoSwap(String oldBattery, int oldSoc, String offerBattery, long delayMs) {
        this.oldBatteryCode = oldBattery;
        this.oldBatterySoc = oldSoc;
        this.offerBatteryCode = offerBattery;
        this.autoSwapDelayMs = Math.max(200L, delayMs);
        this.autoSwap = true;
    }

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

    public String deviceId() {
        return deviceId;
    }

    public String productKey() {
        return productKey;
    }

    public CabinetDevice cabinet() {
        return cabinet;
    }

    public FaultPolicy faults() {
        return faults;
    }

    public boolean isConnected() {
        return running.get() && client != null;
    }

    public long commandsReceived() {
        return commandsReceived.get();
    }

    public long repliesSent() {
        return repliesSent.get();
    }

    public long eventsSent() {
        return eventsSent.get();
    }

    public long telemetrySent() {
        return telemetrySent.get();
    }

    public long rejectedInbound() {
        return rejectedInbound.get();
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
        // 发布流必须在 connect() 内同步注册、再起消费线程。
        // 这不是风格问题：已实测证明 publishes() 不回溯投递，注册前到达的下行报文直接丢失
        // （下面那个窗口存在时，回归用例会以 received=0 失败，与 CI 形状一致）。
        publishes = client.publishes(MqttGlobalPublishFilter.ALL);
        running.set(true);
        Thread consumer = new Thread(this::consumeLoop, "sim-" + deviceId + "-consumer");
        consumer.setDaemon(true);
        consumer.start();
    }

    /** 模拟设备断电重连：换新 sessionId 并重新订阅（FI-10 的前半段）。 */
    public void reconnect() {
        previousSessionId = sessionId;
        disconnect();
        sessionId = "s-" + UUID.randomUUID().toString().substring(0, 12);
        connect();
    }

    public String previousSessionId() {
        return previousSessionId;
    }

    private void consumeLoop() {
        Mqtt5Publishes stream = publishes;
        if (stream == null) {
            return;
        }
        while (running.get()) {
            try {
                Optional<Mqtt5Publish> next = stream.receive(300, TimeUnit.MILLISECONDS);
                if (next.isPresent()) {
                    handleCommand(next.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // 只打 message 会把“哪个主题发不出去”丢掉，跨进程联跑调试全靠这行
                System.err.println("[sim:" + deviceId + "] 处理下行异常：" + e);
                e.printStackTrace();
            }
        }
    }

    private void handleCommand(Mqtt5Publish publish) {
        byte[] payload = publish.getPayloadAsBytes();
        boolean critical = publish.getTopic().toString().endsWith("/cmd/critical");
        lastTrustedNowMs = System.currentTimeMillis();
        commandsReceived.incrementAndGet();
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
                // FI-10：断线重连后用**旧会话号**补发一条迟到的应答。
                // 必须有旧会话存在才注入得成：没重连过就没有"旧会话"，硬伪造会变成测错东西。
                if (faults.staleSession(cmd) && previousSessionId != null) {
                    SimProtocol.Envelope stale = reSigned(outcome.envelope(), outcome.envelope().issuedAt(),
                            outcome.envelope().expireAt(), SimProtocol.messageSecret(masterSecret));
                    SimProtocol.Envelope oldSession = new SimProtocol.Envelope(stale.v(), stale.msgId(),
                            stale.issuedAt(), stale.expireAt(), stale.nonce(), null, previousSessionId,
                            stale.from(), stale.via(), stale.seq(), stale.cmd(), stale.code(), stale.data(), null);
                    String sign = SimProtocol.sign(SimProtocol.messageSecret(masterSecret), oldSession);
                    SimProtocol.Envelope signedOld = new SimProtocol.Envelope(oldSession.v(), oldSession.msgId(),
                            oldSession.issuedAt(), oldSession.expireAt(), oldSession.nonce(), null, oldSession.sessionId(),
                            oldSession.from(), oldSession.via(), oldSession.seq(), oldSession.cmd(), oldSession.code(),
                            oldSession.data(), sign);
                    publishBytes("swap/v1/up/" + productKey + "/" + deviceId + "/cmd_reply",
                            SimProtocol.encode(signedOld), cmd);
                    faults.recordFired("staleSessionReply:" + cmd + "=" + previousSessionId);
                }
            }
            // 关键：重复指令不是"忽略"，而是重放上次应答；否则云侧拿不到回执会误判超时
            case DUPLICATE_REPLAYED -> publishEnvelope(outcome.envelope(), cmd);
            default -> {
                rejectedInbound.incrementAndGet();
                publishEnvelope(rejectReply(outcome, payload), cmd);
            }
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
            publishEnvelope(reply, cmd);
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

    private void publishEnvelope(SimProtocol.Envelope envelope, String scope) {
        if (envelope == null) {
            return;
        }
        publishBytes("swap/v1/up/" + productKey + "/" + deviceId + "/cmd_reply",
                mutateAndEncode(envelope, scope), scope);
        // FI-07：同 sign+nonce 原样重发（重放的是**同一份字节**，不是再生成一条相似报文，
        // 否则测的就不是云侧的重放拦截，而是它能不能分辨两条不同报文）
        int replay = faults.replayTimes(scope);
        for (int i = 0; i < replay; i++) {
            byte[] same = mutateAndEncode(envelope, scope);
            publishBytes("swap/v1/up/" + productKey + "/" + deviceId + "/cmd_reply", same, scope);
            faults.recordFired("replayMessage:" + scope + "#" + (i + 1));
        }
    }

    /**
     * 把要上行的信封按故障策略变形后再编码。
     *
     * 三种注入都改的是**报文本身**：过期（expireAt 已到）、错签（用错的密钥签）、签名后篡改 data。
     * 这里不能"顺手"补一个默认值：FI-08 的两种形态在云侧留痕里是同一条 E1001，
     * 但现场意义完全不同（一个是密钥不同步，一个是报文被改过），所以必须由用例写明用哪种。
     */
    private byte[] mutateAndEncode(SimProtocol.Envelope envelope, String scope) {
        int expired = faults.expiredSeconds(scope);
        if (expired > 0) {
            long now = System.currentTimeMillis();
            SimProtocol.Envelope stale = reSigned(envelope, now - 1_000L, now - expired * 1_000L,
                    SimProtocol.messageSecret(masterSecret));
            faults.recordFired("expiredMessage:" + scope + "=" + expired + "s");
            return SimProtocol.encode(stale);
        }
        String badMode = faults.badSignatureMode(scope);
        if ("SIGN".equals(badMode)) {
            SimProtocol.Envelope wrongKey = reSigned(envelope, envelope.issuedAt(), envelope.expireAt(),
                    SimProtocol.messageSecret("wrong-master-secret"));
            faults.recordFired("badSignature:" + scope + "=SIGN");
            return SimProtocol.encode(wrongKey);
        }
        if ("TAMPER".equals(badMode)) {
            byte[] encoded = SimProtocol.encode(envelope);
            // 签名后篡改：在已编码报文里插一个字段（不改 sign），云侧验签必须失败
            String json = new String(encoded, StandardCharsets.UTF_8);
            int brace = json.indexOf('{');
            String tampered = brace < 0 ? json
                    : json.substring(0, brace + 1) + "\"tampered\":true," + json.substring(brace + 1);
            faults.recordFired("badSignature:" + scope + "=TAMPER");
            return tampered.getBytes(StandardCharsets.UTF_8);
        }
        return SimProtocol.encode(envelope);
    }

    private SimProtocol.Envelope reSigned(SimProtocol.Envelope source, long issuedAt, long expireAt, String secret) {
        SimProtocol.Envelope unsigned = new SimProtocol.Envelope(source.v(), source.msgId(), issuedAt, expireAt,
                source.nonce(), null, source.sessionId(), source.from(), source.via(), source.seq(),
                source.cmd(), source.code(), source.data(), null);
        String sign = SimProtocol.sign(secret, unsigned);
        return new SimProtocol.Envelope(unsigned.v(), unsigned.msgId(), unsigned.issuedAt(), unsigned.expireAt(),
                unsigned.nonce(), null, unsigned.sessionId(), unsigned.from(), unsigned.via(),
                unsigned.seq(), unsigned.cmd(), unsigned.code(), unsigned.data(), sign);
    }

    private void publishBytes(String topic, byte[] payload, String scope) {
        client.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).payload(payload).send();
        if (topic.endsWith("/cmd_reply")) {
            repliesSent.incrementAndGet();
        } else if (topic.endsWith("/event")) {
            eventsSent.incrementAndGet();
        } else if (topic.endsWith("/telemetry")) {
            // 遥测单独数：它走另一个主题，混进 eventsSent 后"事件丢没丢"就分不出是哪一类
            telemetrySent.incrementAndGet();
        }
    }

    private void scheduleAutoSwap(String cmd, int slotNo) {
        System.out.println("[sim:" + deviceId + "] 收到指令 " + cmd + " slot=" + slotNo
                + " autoSwap=" + autoSwap);
        if (!autoSwap || slotNo <= 0) {
            return;
        }
        // 自动把“用户的物理动作”接在门开之后：跨进程联跑需要的是一个完整的对手方，
        // 而不是只能人工触发的半具设备。延迟模拟真人操作（不能同毫秒完成，否则测不出中间态）。
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(autoSwapDelayMs);
                if ("OPEN_SLOT".equals(cmd)) {
                    System.out.println("[sim:" + deviceId + "] 自动动作：投入 " + oldBatteryCode + " 到仓 " + slotNo);
                    simulateUserInsert(slotNo, oldBatteryCode, oldBatterySoc);
                } else if ("UNLOCK_SLOT".equals(cmd)) {
                    System.out.println("[sim:" + deviceId + "] 自动动作：从仓 " + slotNo + " 取走 " + offerBatteryCode);
                    simulateUserTake(slotNo, offerBatteryCode, 100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                System.err.println("[sim:autoSwap] " + e.getMessage());
            }
        }, "sim-auto-swap-" + deviceId);
        worker.setDaemon(true);
        worker.start();
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
                boolean sendEvents = !faults.suppressEvent(cmd);
                if (faults.reorderEvents(cmd)) {
                    // FI-05：先报 close 再报 open。这里的"物理上说不通"正是目的：
                    // 云侧必须靠 fromState 谓词与事实表判定，而不是靠报文到达顺序。
                    publishDoorEvent(slotNo, "door_close", "REORDERED", sendEvents);
                    publishDoorEvent(slotNo, "door_open", "CMD", sendEvents);
                    faults.recordFired("outOfOrder:" + cmd + ":" + slotNo);
                } else {
                    publishDoorEvent(slotNo, "door_open", "CMD", sendEvents);
                }
                for (int i = 0; i < faults.flapTimes(cmd); i++) {
                    // FI-09：同仓在极短窗口反复开合（门磁抖），云侧应合并而不是当成多次事实
                    cabinet.closeDoor(slotNo);
                    publishDoorEvent(slotNo, "door_close", "FLAP", sendEvents);
                    cabinet.openDoor(slotNo);
                    publishDoorEvent(slotNo, "door_open", "FLAP", sendEvents);
                    faults.recordFired("doorFlap:" + slotNo + "#" + (i + 1));
                }
                String alarm = faults.alarmCode(cmd);
                if (alarm != null) {
                    publishSafetyAlarm(alarm);
                }
                scheduleAutoSwap(cmd, slotNo);
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

    /** 用户取走满电电池；FI-13 时不发 door_close，FI-12 时根本没取走却把门关上。 */
    public void simulateUserTake(int slotNo, String batteryCode, int soc) {
        if (faults.closeWithoutTake()) {
            // FI-12：弹仓后用户没动手，自己把门关上——电池仍在仓里。
            // 关键是不能发 battery_taken：只要没这个事实，云侧就不该扣权益（那是它的职责，不是这里的）。
            cabinet.insert(slotNo, batteryCode, soc, 30.0);
            cabinet.closeDoor(slotNo);
            publishEvent("door_close", MAPPER.createObjectNode().put("slotNo", slotNo).put("magnet", "CLOSED"));
            faults.recordFired("closeWithoutTake:" + slotNo);
            return;
        }
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

    /** 用户投入旧电池后关门（door_close 与 battery_detected 是两个事实）；FI-11 时只投入不关门。 */
    public void simulateUserInsert(int slotNo, String batteryCode, int soc) {
        cabinet.insert(slotNo, batteryCode, soc, 30.0);
        publishEvent("battery_detected", MAPPER.createObjectNode()
                .put("slotNo", slotNo).put("batteryCode", batteryCode).put("soc", soc));
        if (faults.insertWithoutClose()) {
            // FI-11：投了就走。仓里已经有电池、门却开着，云侧必须把这块电池转"待取回"而不是回池。
            faults.recordFired("insertNoClose:" + slotNo);
            return;
        }
        cabinet.closeDoor(slotNo);
        publishEvent("door_close", MAPPER.createObjectNode().put("slotNo", slotNo).put("magnet", "CLOSED"));
    }

    public void publishTelemetry(ObjectNode props) {
        String bad = faults.badProperty("TELEMETRY");
        if (bad != null) {
            // FI-15：不合物模型的两种形态都由参数决定：值类型写错，或上报一个物模型里根本没有的键。
            // 两者在云侧都是"拒收这一条而不是阻断主链路"，但留痕里的 reject_step 不同。
            if (props.has(bad)) {
                props.put(bad, "NOT_A_NUMBER");
            } else {
                props.put(bad, 1);
            }
            faults.recordFired("badThingModel:" + bad);
        }
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

    /** 控制面代用户关门（现场运维用；与“取走”是两个动作，所以单独一个入口）。 */
    public void publishDoorClose(int slotNo) {
        publishEvent("door_close", MAPPER.createObjectNode().put("slotNo", slotNo).put("magnet", "CLOSED"));
    }

    /** 控制面直接报一个告警（故 FI-14 之外的告警通道也能驱动：如运维现场报 SMOKE）。 */
    public void publishAlarmEvent(String code, String level) {
        publishEvent("alarm", MAPPER.createObjectNode()
                .put("code", code).put("level", level)
                .put("cabinetTemp", cabinet.cabinetTemp())
                .put("smoke", cabinet.smoke()));
    }

    private void publishAlarm(List<Integer> stopped) {
        publishEvent("alarm", MAPPER.createObjectNode()
                .put("code", "TEMP_HIGH").put("level", "CRITICAL")
                .put("cabinetTemp", cabinet.cabinetTemp())
                .put("stoppedSlots", stopped.size()));
    }

    /**
     * FI-14：主动报一个安全告警并就地自停充电。
     *
     * 为什么设备侧自己也要停：协议 §4.3 写明"设备收不到 EMERGENCY_STOP 也必须自停"——
     * 安全动作不能只有一个执行者（云侧挂了或网络抖了，柜机就继续烤电池）。
     */
    private void publishSafetyAlarm(String code) {
        List<Integer> stopped = cabinet.stopAllCharging();
        publishEvent("alarm", MAPPER.createObjectNode()
                .put("code", code).put("level", "CRITICAL")
                .put("cabinetTemp", cabinet.cabinetTemp())
                .put("stoppedSlots", stopped.size())
                .put("selfStop", true));
        faults.recordFired("safetyAlarm:" + code);
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
        // 作用域（scope）是故障匹配的钥匙：事件按 eventType、遥测按 TELEMETRY，
        // 否则"只在某条报文中注入"这种编排根本实不出来。
        String scope = data.has("eventType") ? data.path("eventType").asText() : "TELEMETRY";
        publishBytes(topic, mutateAndEncode(envelope, scope), scope);
        for (int i = 0; i < faults.replayTimes(scope); i++) {
            publishBytes(topic, mutateAndEncode(envelope, scope), scope);
            faults.recordFired("replayMessage:" + scope + "#" + (i + 1));
        }
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
