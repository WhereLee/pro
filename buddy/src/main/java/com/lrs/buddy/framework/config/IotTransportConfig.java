package com.lrs.buddy.framework.config;

import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.envelope.JsonPayloadCodec;
import com.lrs.buddy.framework.iot.gateway.DeviceGateway;
import com.lrs.buddy.framework.iot.gateway.Gateways;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.repo.IngestDao;
import com.lrs.buddy.framework.iot.security.DeviceCredentialService;
import com.lrs.buddy.framework.iot.security.DeviceSecrets;
import com.lrs.buddy.framework.iot.security.InternalClientSecrets;
import com.lrs.buddy.framework.iot.session.DeviceSessionService;
import com.lrs.buddy.framework.iot.session.EndpointRegistry;
import com.lrs.buddy.framework.iot.transport.BrokerLifecycle;
import com.lrs.buddy.framework.iot.transport.CloudMqttLink;
import com.lrs.buddy.framework.iot.transport.DedupService;
import com.lrs.buddy.framework.iot.transport.InboundRouter;
import com.lrs.buddy.framework.iot.transport.MqttSecurityPolicies;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 设备接入层装配（framework/iot）。
 *
 * 一个必须解释的取舍：ingest 线程池满了是丢弃还是回压？
 * 这里选"丢弃并计数"。理由：这条链路的正确性不依赖它 ——
 * 遥测本来就是 QoS0 可丢，事实类 QoS1 会由设备重投并走去重分支；
 * 若改成 CallerRuns，慢数据库会把 Broker 的事件循环拖住，
 * 一台柜机的慢查询就让同线程上所有设备超时 —— 那是把故障从一条链路放大到所有链路。
 */
@Slf4j
@Configuration
public class IotTransportConfig {

    @Bean
    public JsonPayloadCodec jsonPayloadCodec() {
        return new JsonPayloadCodec();
    }

    @Bean
    public DeviceSecrets deviceSecrets(JsonPayloadCodec codec) {
        return new DeviceSecrets(codec);
    }

    @Bean
    public DeviceDirectoryDao deviceDirectoryDao(JdbcTemplate jdbc) {
        return new DeviceDirectoryDao(jdbc);
    }

    @Bean
    public IngestDao ingestDao(JdbcTemplate jdbc) {
        return new IngestDao(jdbc);
    }

    @Bean
    public DedupService dedupService(IngestDao ingestDao, IotProperties properties, MeterRegistry registry) {
        return new DedupService(ingestDao, properties, registry);
    }

    @Bean
    public DeviceCredentialService deviceCredentialService(DeviceDirectoryDao deviceDao,
                                                           StringRedisTemplate redisTemplate,
                                                           DeviceSecrets secrets, IotProperties properties) {
        return new DeviceCredentialService(deviceDao, redisTemplate, secrets, properties);
    }

    @Bean
    public InternalClientSecrets internalClientSecrets(StringRedisTemplate redisTemplate, IotProperties properties) {
        return new InternalClientSecrets(redisTemplate, properties);
    }

    @Bean
    public DeviceSessionService deviceSessionService(DeviceDirectoryDao deviceDao, IotProperties properties) {
        return new DeviceSessionService(deviceDao, properties);
    }

    @Bean
    public EndpointRegistry endpointRegistry(StringRedisTemplate redisTemplate, IotProperties properties) {
        return new EndpointRegistry(redisTemplate, properties.getNodeId(), properties.getDefaultHeartbeatSeconds());
    }

    @Bean
    public MqttSecurityPolicies mqttSecurityPolicies(DeviceCredentialService credentials,
                                                     InternalClientSecrets internalClientSecrets,
                                                     DeviceDirectoryDao deviceDao) {
        return new MqttSecurityPolicies(credentials, internalClientSecrets, deviceDao);
    }

    /** 业务处理线程池：与 Broker 的事件循环隔离（D3）。 */
    @Bean(name = "iotIngestExecutor")
    public Executor iotIngestExecutor(IotProperties properties, MeterRegistry registry) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(properties.getIngestPoolSize(),
                properties.getIngestPoolSize(), 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(properties.getIngestQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "iot-ingest-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                (rejected, executor) -> {
                    registry.counter("iot.ingest.dropped").increment();
                    log.warn("ingest 队列已满，丢弃一条上行；计数 iot.ingest.dropped");
                });
        new io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics(
                pool, "iot.ingest.executor", List.of()).bindTo(registry);
        return pool;
    }

    @Bean
    public InboundRouter inboundRouter(DeviceDirectoryDao deviceDao, DeviceCredentialService credentials,
                                        IngestDao ingestDao, JsonPayloadCodec codec, DeviceSecrets secrets,
                                        DedupService dedup, Executor iotIngestExecutor,
                                        List<InboundRouter.InboundListener> listeners,
                                        DeviceSessionService sessions, EndpointRegistry endpoints,
                                        MeterRegistry registry) {
        return new InboundRouter(deviceDao, credentials, ingestDao, codec, secrets, dedup,
                iotIngestExecutor, listeners, sessions, endpoints, registry);
    }

    @Bean
    public BrokerLifecycle mqttBrokerLifecycle(IotProperties properties, MqttSecurityPolicies policies,
                                               InboundRouter router, DeviceSessionService sessions,
                                               EndpointRegistry endpoints) {
        return new BrokerLifecycle(properties, policies, router, sessions, endpoints);
    }

    @Bean
    public CloudMqttLink cloudMqttLink(IotProperties properties, InboundRouter router, MeterRegistry registry,
                                       InternalClientSecrets internalClientSecrets) {
        return new CloudMqttLink(properties, router, registry, internalClientSecrets);
    }

    @Bean
    public DeviceGateway deviceGateway(IotProperties properties, EndpointRegistry endpoints,
                                       StringRedisTemplate redisTemplate, CloudMqttLink link) {
        return properties.isClientMode()
                ? new Gateways.External(link)
                : new Gateways.Embedded(endpoints, redisTemplate);
    }

    /**
     * 启动顺序编排：Broker 先起来，云侧链路（client 模式）才能连上。
     * 阶段比 Broker 大，保证同一 Lifecycle 组内后启动、先停止。
     */
    @Bean
    public SmartLifecycle cloudMqttLinkStarter(IotProperties properties, CloudMqttLink link) {
        return new SmartLifecycle() {

            private boolean started;

            @Override
            public void start() {
                if (!properties.isClientMode()) {
                    return;
                }
                link.start();
                started = true;
            }

            @Override
            public void stop() {
                if (!started) {
                    return;
                }
                started = false;
                link.close();
            }

            @Override
            public boolean isRunning() {
                return started;
            }

            @Override
            public int getPhase() {
                return BrokerLifecycle.PHASE + 100;
            }
        };
    }
}
