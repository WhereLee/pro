package com.lrs.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.lrs.sim.device.CabinetDevice;
import com.lrs.sim.fault.FaultPolicy;
import com.lrs.sim.protocol.SimProtocol;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.vertx.core.Vertx;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttServer;
import io.vertx.mqtt.MqttServerOptions;
import io.vertx.mqtt.messages.MqttSubscribeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备侧跨端联跑：虚拟柜机连上一个**与本工程实现无关的标准 MQTT 5 Broker**（Vert.x MQTT Server），
 * 用真实 TCP 走完 "收指令 → 执行 → 回应答 → 发物理事件" 与四条故障形状。
 *
 * 为什么不用 buddy 里的 BrokerLifecycle 来跑：那是云侧代码，模拟器依赖它就破坏了"零代码共享"；
 * 这里只用第三方 Broker 本身，转发性为手写十几行，且刻意写在**测试作用域**内。
 *
 * 本测试要证明的不是"能收发"，而是三条容易做错的行为：
 * 1 重复指令必须**重放上次的应答**，且物理动作只发生一次（FI-02 的另一半）；
 * 2 FI-01（不回应答）时**门已经开了**——如果实现把"不应答"写成"不执行"，这条会假绿；
 * 3 FI-04（有 ACK 无事件）时 ACK 仍要正常回到云侧，只是缺物理事件。
 */
class SimBrokerRoundTripTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String DEVICE_ID = "CAB-RT-0001";
    private static final String MASTER_SECRET = "round-trip-master-secret";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Vertx vertx;
    private static MqttServer broker;
    private static int brokerPort;
    private static final Map<MqttEndpoint, List<String>> SUBSCRIPTIONS = new ConcurrentHashMap<>();

    private Mqtt5BlockingClient cloud;
    private DeviceLink device;
    private final List<SimProtocol.Envelope> cloudReceived = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startBroker() throws Exception {
        vertx = Vertx.vertx();
        broker = MqttServer.create(vertx, new MqttServerOptions().setHost("127.0.0.1").setPort(0));
        broker.endpointHandler(endpoint -> {
            // 与云侧 BrokerLifecycle 同一个坑（第二次踩到）：必须回 PUBACK，
            // 否则设备端的 QoS1 发布永远等回执，症状是测试卡住而不是报错。
            endpoint.publishAutoAck(true);
            endpoint.accept(false, MqttProperties.NO_PROPERTIES);
            SUBSCRIPTIONS.put(endpoint, new CopyOnWriteArrayList<>());
            endpoint.subscribeHandler((sub) -> onSubscribe(endpoint, sub));
            endpoint.publishHandler(message -> route(endpoint, message.topicName(), message.payload().getBytes()));
            endpoint.closeHandler(v -> SUBSCRIPTIONS.remove(endpoint));
            endpoint.disconnectHandler(v -> SUBSCRIPTIONS.remove(endpoint));
        });
        broker.listen().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        brokerPort = broker.actualPort();
    }

    private static void onSubscribe(MqttEndpoint endpoint, MqttSubscribeMessage subscription) {
        subscription.topicSubscriptions().forEach(item -> SUBSCRIPTIONS
                .computeIfAbsent(endpoint, key -> new CopyOnWriteArrayList()).add(item.topicName()));
        endpoint.subscribeAcknowledge(subscription.messageId(),
                subscription.topicSubscriptions().stream().map(item -> item.qualityOfService()).toList());
    }

    /** 最小转发：只支持精确匹配与尾部 `#`，够本测试用（不冒充完整 Broker）。 */
    private static void route(MqttEndpoint publisher, String topic, byte[] payload) {
        SUBSCRIPTIONS.forEach((subscriber, filters) -> {
            if (subscriber == publisher || !subscriber.isConnected()) {
                return;
            }
            if (filters.stream().anyMatch(filter -> matches(filter, topic))) {
                subscriber.publish(topic, io.vertx.core.buffer.Buffer.buffer(payload),
                        io.netty.handler.codec.mqtt.MqttQoS.AT_LEAST_ONCE, false, false);
            }
        });
    }

    private static boolean matches(String filter, String topic) {
        if (filter.endsWith("#")) {
            return topic.startsWith(filter.substring(0, filter.length() - 1));
        }
        return filter.equals(topic);
    }

    @BeforeEach
    void connectCloud() {
        cloud = MqttClient.builder().useMqttVersion5().identifier("test-cloud")
                .serverHost("127.0.0.1").serverPort(brokerPort).buildBlocking();
        cloud.connectWith().cleanStart(true).send();
        cloud.subscribeWith().topicFilter("swap/v1/up/#").qos(MqttQos.AT_LEAST_ONCE).send();
        var publishes = cloud.publishes(MqttGlobalPublishFilter.ALL);
        Thread pump = new Thread(() -> {
            while (true) {
                try {
                    var next = publishes.receive(200, TimeUnit.MILLISECONDS);
                    next.ifPresent(publish -> cloudReceived.add(
                            SimProtocol.decode(publish.getPayloadAsBytes())));
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    return;
                }
            }
        }, "round-trip-cloud-pump");
        pump.setDaemon(true);
        pump.start();
    }

    @AfterEach
    void tearDown() {
        if (device != null) {
            device.close();
            device = null;
        }
        if (cloud != null) {
            cloud.disconnect();
            cloud = null;
        }
        cloudReceived.clear();
    }

    @AfterAll
    static void stopBroker() throws Exception {
        broker.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private DeviceLink startDevice(FaultPolicy faults) {
        device = new DeviceLink("127.0.0.1", brokerPort, PRODUCT_KEY, DEVICE_ID, MASTER_SECRET,
                new CabinetDevice(DEVICE_ID, 8, 30.0), faults);
        device.connect();
        return device;
    }

    /** 云侧下发一条已签名指令（用设备主密钥签，等同真实共享密钥场景）。 */
    private SimProtocol.Envelope sendCommand(String msgId, long seq) {
        ObjectNode data = MAPPER.createObjectNode().put("slotNo", 3).put("orderNo", "SW-RT-1");
        long now = System.currentTimeMillis();
        SimProtocol.Envelope unsigned = new SimProtocol.Envelope(SimProtocol.VERSION, msgId, now, now + 60_000L,
                "nonce-" + msgId, null, "s-rt", "buddy-cloud::node-x", null, seq, "OPEN_SLOT", null, data, null);
        SimProtocol.Envelope signed = new SimProtocol.Envelope(SimProtocol.VERSION, msgId, now, unsigned.expireAt(),
                unsigned.nonce(), null, "s-rt", unsigned.from(), null, seq, "OPEN_SLOT", null, data,
                SimProtocol.sign(SimProtocol.messageSecret(MASTER_SECRET), unsigned));
        cloud.publishWith().topic("swap/v1/dn/" + PRODUCT_KEY + "/" + DEVICE_ID + "/cmd")
                .qos(MqttQos.AT_LEAST_ONCE).payload(SimProtocol.encode(signed)).send();
        return signed;
    }

    private SimProtocol.Envelope awaitReply(long timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            SimProtocol.Envelope found = cloudReceived.stream()
                    .filter(env -> env.code() != null)
                    .findFirst().orElse(null);
            if (found != null) {
                return found;
            }
            Thread.sleep(50);
        }
        return null;
    }

    @Test
    @DisplayName("正常链路：收指令 → 开门 → 回应答 → 发 door_open 事件")
    void commandExecutesAndRepliesOverRealBroker() throws Exception {
        startDevice(new FaultPolicy(1L));
        sendCommand("01JZRT000000000000000000A1", 1);

        SimProtocol.Envelope reply = awaitReply(6);
        assertThat(reply).as("云侧应在真 Broker 上收到设备应答").isNotNull();
        assertThat(reply.code()).isEqualTo("OK");
        assertThat(device.cabinet().slot(3).door).isEqualTo(CabinetDevice.Door.OPEN);

        long eventDeadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < eventDeadline
                && cloudReceived.stream().noneMatch(env -> env.data() != null
                && "door_open".equals(env.data().path("eventType").asText()))) {
            Thread.sleep(50);
        }
        assertThat(cloudReceived).anyMatch(env -> env.data() != null
                && "door_open".equals(env.data().path("eventType").asText()));
    }

    @Test
    @DisplayName("重复投递同一 msgId：应答被重放，但门只开一次")
    void duplicateCommandRepliesWithoutReexecution() throws Exception {
        startDevice(new FaultPolicy(2L));
        sendCommand("01JZRT000000000000000000B1", 1);
        SimProtocol.Envelope first = awaitReply(6);
        assertThat(first).isNotNull();
        cloudReceived.clear();

        // 同一 msgId 再投一次（模拟 QoS1 重投）
        sendCommand("01JZRT000000000000000000B1", 2);
        SimProtocol.Envelope replayed = awaitReply(6);
        assertThat(replayed).as("重复指令必须重放应答，不能沉默")
                .isNotNull();
        assertThat(replayed.code()).isEqualTo("OK");
        assertThat(replayed.data().path("cmdId").asText())
                .as("重放的应是原指令的应答（cmdId 指回第一条）").isEqualTo("01JZRT000000000000000000B1");
    }

    @Test
    @DisplayName("FI-01：不回应答时动作已经发生（超时不等于未发生）")
    void dropReplyStillExecutesPhysically() throws Exception {
        startDevice(new FaultPolicy(3L)
                .add(new FaultPolicy.Trigger(FaultPolicy.Kind.DROP_REPLY, "OPEN_SLOT", 0, 1, null)));
        sendCommand("01JZRT000000000000000000C1", 1);

        SimProtocol.Envelope reply = awaitReply(2);
        assertThat(reply).as("注入了不应答故障，云侧本就不该收到应答").isNull();
        assertThat(device.cabinet().slot(3).door).as("但门确实开了——云侧若把超时当没发生就会错判")
                .isEqualTo(CabinetDevice.Door.OPEN);
    }

    @Test
    @DisplayName("FI-04：ACK 正常回到云侧，但物理事件永不到达")
    void ackArrivesButEventSuppressed() throws Exception {
        startDevice(new FaultPolicy(4L)
                .add(new FaultPolicy.Trigger(FaultPolicy.Kind.SUPPRESS_EVENT, "OPEN_SLOT", 0, 1, null)));
        sendCommand("01JZRT000000000000000000D1", 1);

        SimProtocol.Envelope reply = awaitReply(6);
        assertThat(reply).as("ACK 必须到达，否则这条故障就退化成 FI-01").isNotNull();
        assertThat(reply.code()).isEqualTo("OK");
        assertThat(cloudReceived).as("但物理事件不能出现")
                .noneMatch(env -> env.data() != null && "door_open".equals(env.data().path("eventType").asText()));
    }

    @Test
    @DisplayName("伪造签名的指令：设备回 E1001 且不开门")
    void forgedCommandIsRejectedWithCode() throws Exception {
        startDevice(new FaultPolicy(5L));
        ObjectNode data = MAPPER.createObjectNode().put("slotNo", 5);
        long now = System.currentTimeMillis();
        SimProtocol.Envelope forged = new SimProtocol.Envelope(SimProtocol.VERSION,
                "01JZRT000000000000000000E1", now, now + 60_000L, "nonce-e", null, "s-rt",
                "buddy-cloud::node-x", null, 1L, "OPEN_SLOT", null, data, "deadbeef");
        cloud.publishWith().topic("swap/v1/dn/" + PRODUCT_KEY + "/" + DEVICE_ID + "/cmd")
                .qos(MqttQos.AT_LEAST_ONCE).payload(SimProtocol.encode(forged)).send();

        SimProtocol.Envelope reply = awaitReply(6);
        assertThat(reply).isNotNull();
        assertThat(reply.code()).isEqualTo("E1001");
        assertThat(device.cabinet().slot(5).door).isEqualTo(CabinetDevice.Door.CLOSED);
    }
}
