package com.lrs.buddy.framework.iot.transport;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.security.InternalClientSecrets;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 云侧与 Broker 的内部链路：订阅上行、发布下行。
 *
 * 关键设计（swap-protocol.md §2.1、§7.5）：
 * 云侧走标准 MQTT 客户端接入自己的 Broker，而不是用 Broker 的内部 API。
 * 这样"换 Broker"（本地 Moquette 到生产 EMQX）不改业务代码；
 * 反之若直接调 Moquette 的发布接口，EMQX 那边就得整套重写。
 *
 * 消费线程独立于 Broker 的 event loop，并把报文交给 ingest 线程池（见 InboundRouter）。
 */
@Slf4j
public class CloudMqttLink implements AutoCloseable {

    private final IotProperties properties;
    private final InboundRouter router;
    private final MeterRegistry registry;
    private final InternalClientSecrets internalClientSecrets;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile Mqtt5BlockingClient client;
    private Thread consumer;

    public CloudMqttLink(IotProperties properties, InboundRouter router, MeterRegistry registry,
                         InternalClientSecrets internalClientSecrets) {
        this.properties = properties;
        this.router = router;
        this.registry = registry;
        this.internalClientSecrets = internalClientSecrets;
    }

    /** 内部客户端 ID：ACL 靠它区分"云侧可读全部上行"与"设备只能读自己"。 */
    public String cloudClientId() {
        return BrokerLifecycle.CLOUD_CLIENT_PREFIX + "::" + properties.getNodeId();
    }

    public void start() {
        if (!properties.isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        // username 与 password 必须来自同一个 ts|nonce，分两次生成会导致口令永远校验不过
        String username = internalClientSecrets.username(UUID.randomUUID().toString().replace("-", ""));
        String password = internalClientSecrets.password(username);

        client = MqttClient.builder()
                .useMqttVersion5()
                .identifier(cloudClientId())
                .serverHost(properties.getHost())
                .serverPort(properties.getPort())
                .simpleAuth()
                .username(username)
                .password(ByteBuffer.wrap(password.getBytes(StandardCharsets.UTF_8)))
                .applySimpleAuth()
                .buildBlocking();
        try {
            // cleanStart=true 且不要 Broker 侧会话缓存：我们本来就不把可靠投递押在 Broker 的离线队列上
            // （协议 §2.1，未投递指令由云端 iot_command + Outbox 持久化重投）。
            // 另外 Moquette 在拒绝 CONNECT 时回的是 v3 风格 reason code，v5 客户端会报
            // "wrong reason code" 而不是鉴权失败 —— 所以连不上先查自己的认证，不要相信错误文案。
            client.connectWith()
                    .cleanStart(true)
                    .keepAlive(60)
                    .send();
            client.subscribeWith()
                    .topicFilter(properties.getSubscribeFilter())
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .send();
        } catch (Exception e) {
            running.set(false);
            throw new IllegalStateException("云侧 MQTT 接入失败：" + e.getMessage(), e);
        }
        consumer = new Thread(this::consumeLoop, "iot-ingest-" + properties.getNodeId());
        consumer.setDaemon(true);
        consumer.start();
        log.info("云侧 MQTT 链路已建立，订阅过滤器={}", properties.getSubscribeFilter());
    }

    private void consumeLoop() {
        var publishes = client.publishes(MqttGlobalPublishFilter.ALL);
        while (running.get()) {
            try {
                Optional<Mqtt5Publish> next = publishes.receive(500, TimeUnit.MILLISECONDS);
                if (next.isEmpty()) {
                    continue;
                }
                Mqtt5Publish publish = next.get();
                router.dispatch(publish.getTopic().toString(), publish.getPayloadAsBytes(),
                        cloudClientId(), publish.getQos().getCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // 单条报文处理异常不得终止消费线程，否则整条接入链路会静默停摆
                log.error("云侧消费上行异常，继续消费：{}", e.getMessage(), e);
            }
        }
        publishes.close();
    }

    public void publish(String topic, byte[] payload, MqttQos qos) {
        Mqtt5BlockingClient current = client;
        if (current == null) {
            throw new IllegalStateException("云侧 MQTT 链路未启动");
        }
        try {
            current.publishWith()
                    .topic(topic)
                    .qos(qos)
                    .payload(payload)
                    .send();
            registry.counter("cmd.dispatch.total", "result", "sent").increment();
        } catch (Exception e) {
            registry.counter("cmd.dispatch.total", "result", "error").increment();
            throw new IllegalStateException("下行发布失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (consumer != null) {
            consumer.interrupt();
        }
        Mqtt5BlockingClient current = client;
        if (current != null) {
            try {
                current.disconnectWith().send();
            } catch (Exception e) {
                log.debug("断开云侧连接异常（可忽略）：{}", e.getMessage());
            }
        }
    }
}
