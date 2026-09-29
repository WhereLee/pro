package com.lrs.buddy.framework.config;

import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.envelope.JsonPayloadCodec;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.repo.IngestDao;
import com.lrs.buddy.framework.iot.security.DeviceCredentialService;
import com.lrs.buddy.framework.iot.security.DeviceSecrets;
import com.lrs.buddy.framework.iot.security.InternalClientSecrets;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 设备接入层装配（framework/iot）。
 *
 * 一个必须解释的取舍：ingest 线程池使用 CallerRunsPolicy 还是丢弃？
 * 这里选择"队列满即丢弃并计数"，理由是这条链路的正确性不依赖丢弃与否 ——
 * 遥测 QoS0 本来就可丢，事实类 QoS1 会由设备重投并走去重分支；
 * 若改成 CallerRuns，慢数据库会把 Broker 的 event loop 拖住，
 * 一个柜机的慢查询就会让同线程上的全部设备超时 —— 那是把故障从一条链路放大到所有链路。
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
                                                           org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                                                           DeviceSecrets secrets, IotProperties properties) {
        return new DeviceCredentialService(deviceDao, redisTemplate, secrets, properties);
    }

    @Bean
    public InternalClientSecrets internalClientSecrets(org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                                                       IotProperties properties) {
        return new InternalClientSecrets(redisTemplate, properties);
    }

    @Bean
    public MqttSecurityPolicies.PasswordAuthenticator mqttPasswordAuthenticator(DeviceCredentialService credentials,
                                                                                InternalClientSecrets internalClientSecrets) {
        return new MqttSecurityPolicies.PasswordAuthenticator(credentials, internalClientSecrets);
    }

    @Bean
    public MqttSecurityPolicies.OwnTopicAuthorizer mqttTopicAuthorizer(DeviceDirectoryDao deviceDao) {
        return new MqttSecurityPolicies.OwnTopicAuthorizer(deviceDao);
    }

    /** 业务处理线程池：与 Broker 的 event loop 隔离（D3）。 */
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
                    // 丢弃并计数：让"处理不过来"成为一个可被告警的指标，而不是一个把上游拖死的阻塞
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
                                       List<InboundRouter.InboundListener> listeners) {
        return new InboundRouter(deviceDao, credentials, ingestDao, codec, secrets, dedup,
                iotIngestExecutor, listeners);
    }

    @Bean
    public BrokerLifecycle mqttBrokerLifecycle(IotProperties properties,
                                               MqttSecurityPolicies.PasswordAuthenticator authenticator,
                                               MqttSecurityPolicies.OwnTopicAuthorizer authorizer) {
        return new BrokerLifecycle(properties, authenticator, authorizer);
    }

    @Bean
    public CloudMqttLink cloudMqttLink(IotProperties properties, InboundRouter router, MeterRegistry registry,
                                       InternalClientSecrets internalClientSecrets) {
        return new CloudMqttLink(properties, router, registry, internalClientSecrets);
    }

    /**
     * 启动顺序编排：Broker 先起来，云侧客户端才能连上。
     *
     * BrokerLifecycle 自身是 SmartLifecycle；这里再包一层，让链路阶段比 Broker 更大一点。
     */
    @Bean
    public SmartLifecycle cloudMqttLinkStarter(CloudMqttLink link) {
        return new SmartLifecycle() {

            private boolean started;

            @Override
            public void start() {
                link.start();
                started = true;
            }

            @Override
            public void stop() {
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
