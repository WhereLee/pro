package com.lrs.buddy.framework.iot.transport;

import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.session.DeviceSessionService;
import com.lrs.buddy.framework.iot.session.EndpointRegistry;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Vertx;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttServer;
import io.vertx.mqtt.MqttServerOptions;
import io.vertx.mqtt.MqttTopicSubscription;
import io.vertx.mqtt.messages.MqttSubscribeMessage;
import io.vertx.mqtt.messages.MqttPublishMessage;
import io.vertx.mqtt.messages.codes.MqttSubAckReasonCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 嵌入式 Broker（Vert.x MQTT Server）的生命周期与连接装配。
 *
 * 为什么必须显式管这个阶段（D2）：Spring Boot 的优雅停机默认只管 web 容器，不等自建网络资源。
 * 不做这一步的后果是每次发版都把全部设备连接硬掐断，而千台设备同时重连加认证查库
 * 会形成自我放大的冷启动风暴。
 *
 * 为什么授权写在连接装配里（D6）：订阅与发布在 Broker 层就被判定，
 * 越权的报文根本不会进入业务管道 —— 业务层再判已经晚了。
 */
@Slf4j
public class BrokerLifecycle implements SmartLifecycle {

    /** 云侧内部客户端 ID 前缀，用于与设备客户端区分（认证走内部口令，不建假设备）。 */
    public static final String CLOUD_CLIENT_PREFIX = "buddy-cloud";

    /** 启动阶段：晚于数据源与 Redis，早于云侧链路。 */
    public static final int PHASE = Integer.MAX_VALUE - 1000;

    /** MQTT 5 在 CONNECT 报文里的协议级别；Vert.x 的 protocolVersion() 返回 int 而非枚举。 */
    private static final int PROTOCOL_LEVEL_5 = 5;

    private final IotProperties properties;
    private final MqttSecurityPolicies policies;
    private final InboundRouter router;
    private final DeviceSessionService sessions;
    private final EndpointRegistry endpoints;

    private Vertx vertx;
    private MqttServer server;
    private volatile int boundPort;
    private volatile boolean running;

    public BrokerLifecycle(IotProperties properties, MqttSecurityPolicies policies, InboundRouter router,
                           DeviceSessionService sessions, EndpointRegistry endpoints) {
        this.properties = properties;
        this.policies = policies;
        this.router = router;
        this.sessions = sessions;
        this.endpoints = endpoints;
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            log.info("buddy.iot.enabled=false，跳过 MQTT Broker 启动");
            return;
        }
        vertx = Vertx.vertx();
        server = MqttServer.create(vertx, new MqttServerOptions()
                .setHost(properties.getHost())
                .setPort(properties.getPort())
                // 单报文上限，防超大报文打满内存
                .setMaxMessageSize(64 * 1024));
        server.endpointHandler(this::handle);
        server.exceptionHandler(err -> log.error("Broker 异常：{}", err.getMessage(), err));
        try {
            server.listen(properties.getPort(), properties.getHost())
                    .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
            boundPort = server.actualPort();
            running = true;
            log.info("MQTT Broker 已启动 {}:{}（节点 {}）", properties.getHost(), boundPort, properties.getNodeId());
        } catch (Exception e) {
            throw new IllegalStateException("MQTT Broker 启动失败：" + e.getMessage(), e);
        }
    }

    /** 一条连接：认证 → accept → 装配读写与订阅回调。 */
    private void handle(MqttEndpoint endpoint) {
        String clientId = endpoint.clientIdentifier();
        int version = endpoint.protocolVersion();
        var auth = endpoint.auth();
        String username = auth == null ? null : auth.getUsername();
        String password = auth == null ? null : auth.getPassword();
        var result = policies.authenticate(clientId, username, password);
        if (!result.allowed()) {
            // 拒绝原因不区分"设备不存在"与"口令错"，避免被用来枚举 deviceId
            endpoint.reject(refuseCodeFor(endpoint));
            log.debug("连接被拒绝：clientId={}", clientId);
            return;
        }
        // 先装处理器再回 CONNACK：v5 客户端可以在 CONNACK 后几乎立刻 publish，
        // 装晚一拍就会撞上 "Received an MQTT packet from a not connected client"。
        // 服务端必须回 PUBACK：不回时设备的 QoS1 publish 会永远等下去。
        endpoint.publishAutoAck(true);
        endpoint.publishHandler(message -> onPublish(endpoint, message));
        endpoint.subscribeHandler(subscription -> onSubscribe(endpoint, subscription));
        endpoint.unsubscribeHandler(unsub -> endpoint.unsubscribeAcknowledge(unsub.messageId()));
        endpoint.disconnectHandler(v -> onClosed(result.device(), endpoint, null, "CLIENT_DISCONNECT"));
        endpoint.closeHandler(v -> onClosed(result.device(), endpoint, null, "CONNECTION_CLOSED"));
        acceptBy(endpoint, version);
        String sessionId = sessions.onConnect(result.device(), clientId, properties.getNodeId());
        if (result.device() != null) {
            endpoints.register(result.device().id(), endpoint, sessionId);
        }
    }

    /**
     * 按版本回 CONNACK。
     *
     * MQTT 5 必须用无参 accept()：带 cleanSession 的重载是 3.1.1 的语义（写进了 CONNECT 回包的
     * session-present 位），对 v5 连接用它会让 Vert.x 认为连接尚未建立，
     * 于是 CONNACK 发不出去而客户端永远超时。
     */
    private static void acceptBy(MqttEndpoint endpoint, int version) {
        if (version == PROTOCOL_LEVEL_5) {
            endpoint.accept(false, io.netty.handler.codec.mqtt.MqttProperties.NO_PROPERTIES);
        } else {
            endpoint.accept(false);
        }
    }

    private void onPublish(MqttEndpoint endpoint, MqttPublishMessage message) {
        String topic = message.topicName();
        String clientId = endpoint.clientIdentifier();
        // 跨进程联跑排障的第一道入口：没有这行就无法区分“Broker 未递上来”与“递上来后被丢”
        log.debug("MQTT 上行发布：topic={}, clientId={}, qos={}", topic, clientId, message.qosLevel());
        if (!policies.canWrite(topic, clientId)) {
            // 越权写入：只记日志与指标，不回原因（协议无此通道），并丢弃
            log.warn("拒绝越权发布：clientId={}, topic={}", clientId, topic);
            router.rejected(endpoint, topic);
            return;
        }
        io.vertx.core.buffer.Buffer payload = message.payload();
        byte[] bytes = payload.getBytes();
        router.dispatch(topic, bytes, clientId, message.qosLevel().value());
    }

    /**
     * 订阅授权逐条回执。
     *
     * 用逐条 reason code 而不是整体拒绝：一次 SUBSCRIBE 可以带多个主题，
     * 全拒会让"合法的那条也订不上"，而 MQTT 5 本来就设计成逐条返回码。
     */
    private void onSubscribe(MqttEndpoint endpoint, MqttSubscribeMessage subscription) {
        String clientId = endpoint.clientIdentifier();
        List<MqttSubAckReasonCode> codes = new ArrayList<>();
        for (MqttTopicSubscription sub : subscription.topicSubscriptions()) {
            String filter = sub.topicName();
            boolean allowed = policies.canRead(filter, clientId);
            codes.add(allowed ? grantedFor(sub.qualityOfService()) : MqttSubAckReasonCode.NOT_AUTHORIZED);
            if (!allowed) {
                log.warn("拒绝越权订阅：clientId={}, filter={}", clientId, filter);
            }
        }
        endpoint.subscribeAcknowledge(subscription.messageId(), codes,
                io.netty.handler.codec.mqtt.MqttProperties.NO_PROPERTIES);
    }

    private static MqttSubAckReasonCode grantedFor(MqttQoS requested) {
        return MqttSubAckReasonCode.qosGranted(requested);
    }

    /**
     * 按协议版本选拒因码。
     *
     * netty 把 v3 与 v5 的返回码分成了两套常量（NOT_AUTHORIZED=0x05 与 NOT_AUTHORIZED_5=0x87），
     * 拿 v3 的码回给 v5 客户端，客户端会报 "无法解析 CONNACK" 而不是 "认证失败"，
     * 真正的错因会被一个误导性的文案盖住（上一版 Moquette 的死因就是同一类问题）。
     * 拒因也不区分"设备不存在"与"口令错"，避免被用来枚举 deviceId。
     */
    private static MqttConnectReturnCode refuseCodeFor(MqttEndpoint endpoint) {
        return endpoint.protocolVersion() == PROTOCOL_LEVEL_5
                ? MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED_5
                : MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD;
    }

    private void onClosed(com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao.Device device,
                          MqttEndpoint endpoint, String sessionId, String reason) {
        if (device != null) {
            endpoints.unregister(device.id(), sessionId);
            sessions.onDisconnect(device, sessionId, reason);
        }
        log.debug("连接结束：sessionId={}, reason={}", sessionId, reason);
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            if (server != null) {
                server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("Broker 关闭异常：{}", e.getMessage());
        }
        try {
            if (vertx != null) {
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("Vertx 关闭异常：{}", e.getMessage());
        }
        log.info("MQTT Broker 已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** 实际监听端口（配置为 0 时由系统分配，测试用）。 */
    public int boundPort() {
        return boundPort == 0 ? properties.getPort() : boundPort;
    }

    static String utf8(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
