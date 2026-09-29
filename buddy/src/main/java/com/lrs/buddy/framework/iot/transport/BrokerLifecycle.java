package com.lrs.buddy.framework.iot.transport;

import com.lrs.buddy.framework.iot.config.IotProperties;
import io.moquette.broker.Server;
import io.moquette.broker.config.IConfig;
import io.moquette.broker.config.MemoryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.List;
import java.util.Properties;

/**
 * 嵌入式 Broker 的生命周期管理。
 *
 * 为什么必须显式管这个阶段（D2）：Spring Boot 的优雅停机默认只管 web 容器，
 * 不等自建网络资源。不做这一步的后果是每次发版都把全部设备连接硬掐断，
 * 而千台设备同时重连加认证查库会形成自我放大的冷启动风暴。
 *
 * 顺序：phase 取 Integer.MAX_VALUE - 1000，确保 DataSource/Redis 就绪后才启动；
 * 关闭时先停止监听（不再接受新连接），再等在途处理，最后 stopServer。
 */
@Slf4j
public class BrokerLifecycle implements SmartLifecycle {

    /** 云侧内部客户端 ID 前缀，用于与设备客户端区分（认证走内部口令，不建假设备）。 */
    public static final String CLOUD_CLIENT_PREFIX = "buddy-cloud";

    /** 启动阶段：晚于数据源与 Redis，早于云侧链路。 */
    public static final int PHASE = Integer.MAX_VALUE - 1000;

    private final IotProperties properties;
    private final MqttSecurityPolicies.PasswordAuthenticator authenticator;
    private final MqttSecurityPolicies.OwnTopicAuthorizer authorizer;
    private final Server server = new Server();

    private volatile boolean running;

    public BrokerLifecycle(IotProperties properties,
                           MqttSecurityPolicies.PasswordAuthenticator authenticator,
                           MqttSecurityPolicies.OwnTopicAuthorizer authorizer) {
        this.properties = properties;
        this.authenticator = authenticator;
        this.authorizer = authorizer;
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            log.info("buddy.iot.enabled=false，跳过 MQTT Broker 启动");
            return;
        }
        try {
            server.startServer(config(), List.of(), null, authenticator, authorizer);
            running = true;
            log.info("MQTT Broker 已启动：{}:{}（节点 {}）", properties.getHost(), properties.getPort(),
                    properties.getNodeId());
        } catch (Exception e) {
            throw new IllegalStateException("MQTT Broker 启动失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            server.stopServer();
            log.info("MQTT Broker 已停止");
        } catch (RuntimeException e) {
            log.warn("MQTT Broker 停止异常：{}", e.getMessage());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    public int boundPort() {
        return properties.getPort();
    }

    private IConfig config() {
        Properties p = new Properties();
        p.setProperty(IConfig.HOST_PROPERTY_NAME, properties.getHost());
        p.setProperty(IConfig.PORT_PROPERTY_NAME, String.valueOf(properties.getPort()));
        // 匿名必须关闭：否则任何知道 deviceId 的人都能连上来发伪造遥测
        p.setProperty(IConfig.ALLOW_ANONYMOUS_PROPERTY_NAME, "false");
        // Broker 自身不落盘：可靠性由云端 iot_command 与 Outbox 持久化承担（协议 §2.1）
        p.setProperty(IConfig.PERSISTENCE_ENABLED_PROPERTY_NAME, "false");
        p.setProperty(IConfig.PERSISTENT_QUEUE_TYPE_PROPERTY_NAME, "memory");
        p.setProperty(IConfig.BUFFER_FLUSH_MS_PROPERTY_NAME, "0");
        // 单报文上限，防超大报文打满内存
        p.setProperty(IConfig.NETTY_MAX_BYTES_PROPERTY_NAME, String.valueOf(64 * 1024));
        return new MemoryConfig(p);
    }
}
