package com.lrs.sim;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttServer;
import io.vertx.mqtt.MqttServerOptions;
import io.vertx.mqtt.messages.MqttSubscribeMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 测试作用域的最小 MQTT 5 Broker（Vert.x 标准实现）+ 云侧替身客户端。
 *
 * 为什么存在：设备侧的 FI 矩阵必须在一个进程里同时握住"对手方"和"观测者"两端，
 * 而不能依赖 buddy 起服务（那会把模拟器测试变成集成测试，且破坏零代码共享）。
 *
 * 与 {@link SimBrokerRoundTripTest} 里的内联版本同源，但那份是 M1 的历史用例，
 * 本类只服务新用例；两者转发规则一致（精确匹配 + 尾部 {@code #}），不冒充完整 Broker。
 *
 * 两条实测教训固化在这里，别再踩：
 * 1 {@code publishAutoAck(true)} 必须开，否则 QoS1 发布永远等不到回执，症状是"测试卡住"而不是报错；
 * 2 subscribe 必须回 SUBACK，否则客户端订阅永不确认。
 */
final class SimBrokerHarness implements AutoCloseable {

    private static final Map<MqttEndpoint, List<String>> SUBSCRIPTIONS = new ConcurrentHashMap<>();

    private final Vertx vertx;
    private final MqttServer server;
    private final int port;

    SimBrokerHarness() throws Exception {
        vertx = Vertx.vertx();
        server = MqttServer.create(vertx, new MqttServerOptions().setHost("127.0.0.1").setPort(0));
        server.endpointHandler(endpoint -> {
            endpoint.publishAutoAck(true);
            endpoint.accept(false, MqttProperties.NO_PROPERTIES);
            SUBSCRIPTIONS.put(endpoint, new CopyOnWriteArrayList<>());
            endpoint.subscribeHandler(subscription -> onSubscribe(endpoint, subscription));
            endpoint.publishHandler(message ->
                    route(endpoint, message.topicName(), message.payload().getBytes()));
            endpoint.closeHandler(ignored -> SUBSCRIPTIONS.remove(endpoint));
            endpoint.disconnectHandler(ignored -> SUBSCRIPTIONS.remove(endpoint));
        });
        server.listen().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        port = server.actualPort();
    }

    int port() {
        return port;
    }

    private static void onSubscribe(MqttEndpoint endpoint, MqttSubscribeMessage subscription) {
        subscription.topicSubscriptions().forEach(item -> SUBSCRIPTIONS
                .computeIfAbsent(endpoint, key -> new CopyOnWriteArrayList()).add(item.topicName()));
        endpoint.subscribeAcknowledge(subscription.messageId(),
                subscription.topicSubscriptions().stream().map(item -> item.qualityOfService()).toList());
    }

    private static void route(MqttEndpoint publisher, String topic, byte[] payload) {
        SUBSCRIPTIONS.forEach((subscriber, filters) -> {
            if (subscriber == publisher || !subscriber.isConnected()) {
                return;
            }
            if (filters.stream().anyMatch(filter -> matches(filter, topic))) {
                subscriber.publish(topic, Buffer.buffer(payload),
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

    /** 一个只连不鉴权的观察者客户端（云侧替身由 {@link SimCloudDriver} 扮演，这里给测试做原始观测用）。 */
    Mqtt5BlockingClient observer(String identifier, String topicFilter) {
        Mqtt5BlockingClient client = MqttClient.builder().useMqttVersion5().identifier(identifier)
                .serverHost("127.0.0.1").serverPort(port).buildBlocking();
        client.connectWith().cleanStart(true).send();
        client.subscribeWith().topicFilter(topicFilter).qos(MqttQos.AT_LEAST_ONCE).send();
        return client;
    }

    MqttGlobalPublishFilter allFilter() {
        return MqttGlobalPublishFilter.ALL;
    }

    @Override
    public void close() {
        SUBSCRIPTIONS.clear();
        server.close().toCompletionStage().toCompletableFuture().join();
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }
}
