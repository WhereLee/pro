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

    /** 转发零命中的次数：测试可用它区分“消息没路由”与“设备没处理”。 */
    static final java.util.concurrent.atomic.AtomicLong LOST_ROUTE = new java.util.concurrent.atomic.AtomicLong();

    static long lostRouteCount() {
        return LOST_ROUTE.get();
    }

    private final Vertx vertx;
    private final MqttServer server;
    private final int port;

    SimBrokerHarness() throws Exception {
        vertx = Vertx.vertx();
        server = MqttServer.create(vertx, new MqttServerOptions().setHost("127.0.0.1").setPort(0));
        server.endpointHandler(endpoint -> {
            endpoint.publishAutoAck(true);
            // 处理器必须在 accept() 之前注册：Vert.x 文档要求如此，accept 后连接就开始被处理，
            // 晚设的 handler 会让“到达的第一个包”走空。CI 上的形状正是
            // “第一条下行指令设备 received=0，同场景后续却收到并回 OK”。
            endpoint.subscribeHandler(subscription -> onSubscribe(endpoint, subscription));
            endpoint.publishHandler(message ->
                    route(endpoint, message.topicName(), message.payload().getBytes()));
            endpoint.closeHandler(ignored -> SUBSCRIPTIONS.remove(endpoint));
            endpoint.disconnectHandler(ignored -> SUBSCRIPTIONS.remove(endpoint));
            endpoint.accept(false, MqttProperties.NO_PROPERTIES);
            SUBSCRIPTIONS.put(endpoint, new CopyOnWriteArrayList<>());
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
        // 转发前先数一遍命中：本 harness 不是真 Broker，丢了消息必须能当场看见，
        // 否则设备侧只会表现为“没收到”，排查只能靠猜（这就是 CI 三红的原因）。
        int matched = 0, skipped = 0;
        for (Map.Entry<MqttEndpoint, List<String>> entry : SUBSCRIPTIONS.entrySet()) {
            MqttEndpoint subscriber = entry.getKey();
            if (subscriber == publisher) {
                continue;
            }
            if (!subscriber.isConnected()) {
                skipped++;
                continue;
            }
            if (entry.getValue().stream().anyMatch(filter -> matches(filter, topic))) {
                matched++;
                subscriber.publish(topic, Buffer.buffer(payload),
                        io.netty.handler.codec.mqtt.MqttQoS.AT_LEAST_ONCE, false, false);
            }
        }
        if (matched == 0 && topic.contains("/dn/")) {
            // 只统计下行：上行没有观测者是合法形态（例如规模用例里 1000 台只发不收），
            // 把它也记进来看会把无关噪声当成故障。
            LOST_ROUTE.addAndGet(1);
            System.err.println("[harness] 下行无人命中主题 " + topic + "：订阅表大小=" + SUBSCRIPTIONS.size()
                    + " 因未连接跳过=" + skipped);
        }
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
