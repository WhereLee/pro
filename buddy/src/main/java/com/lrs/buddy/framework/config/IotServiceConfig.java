package com.lrs.buddy.framework.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrs.buddy.framework.event.EventPublisher;
import com.lrs.buddy.framework.event.OutboxDao;
import com.lrs.buddy.framework.event.OutboxEventPublisher;
import com.lrs.buddy.framework.event.StreamRelay;
import com.lrs.buddy.framework.iot.command.CommandReplyListener;
import com.lrs.buddy.framework.iot.command.CommandTimeoutSweeper;
import com.lrs.buddy.framework.iot.command.DeviceCommandService;
import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.envelope.JsonPayloadCodec;
import com.lrs.buddy.framework.iot.gateway.DeviceGateway;
import com.lrs.buddy.framework.iot.maintenance.IotMaintenanceJob;
import com.lrs.buddy.framework.iot.model.ThingModelValidator;
import com.lrs.buddy.framework.iot.repo.CommandDao;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.repo.IngestDao;
import com.lrs.buddy.framework.iot.security.DeviceCredentialService;
import com.lrs.buddy.framework.iot.security.DeviceSecrets;
import com.lrs.buddy.framework.iot.session.EndpointRegistry;
import com.lrs.buddy.framework.iot.telemetry.TelemetryIngestService;
import com.lrs.buddy.framework.iot.telemetry.TelemetryPartitionJob;
import com.lrs.buddy.framework.iot.telemetry.TelemetryStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 指令总线、遥测管道与 Outbox 的装配（framework 层，与业务无关）。
 *
 * 这里刻意不注入任何"业务监听器"：biz/swap 通过实现
 * {@link DeviceCommandService.CommandListener} 与 {@link com.lrs.buddy.framework.iot.transport.InboundRouter.InboundListener}
 * 接入，框架侧只认接口。反过来若框架 import 了 swap 的类，这套基座就不再可复用。
 */
@Configuration
public class IotServiceConfig {

    @Bean
    public CommandDao commandDao(JdbcTemplate jdbc) {
        return new CommandDao(jdbc);
    }

    @Bean
    public DeviceCommandService deviceCommandService(CommandDao commandDao, DeviceDirectoryDao deviceDao,
                                                     DeviceCredentialService credentials, DeviceSecrets secrets,
                                                     JsonPayloadCodec codec, ObjectMapper objectMapper,
                                                     DeviceGateway gateway, EndpointRegistry endpoints,
                                                     IotProperties properties, MeterRegistry registry,
                                                     List<DeviceCommandService.CommandListener> listeners) {
        return new DeviceCommandService(commandDao, deviceDao, credentials, secrets, codec, objectMapper,
                gateway, endpoints, properties, registry, listeners);
    }

    @Bean
    public CommandReplyListener commandReplyListener(DeviceCommandService commands) {
        return new CommandReplyListener(commands);
    }

    @Bean
    public CommandTimeoutSweeper commandTimeoutSweeper(CommandDao commandDao, DeviceCommandService commands,
                                                       IotProperties properties) {
        return new CommandTimeoutSweeper(commandDao, commands, properties);
    }

    @Bean
    public TelemetryStore telemetryStore(IngestDao ingestDao) {
        return new TelemetryStore.MysqlStore(ingestDao);
    }

    @Bean
    public ThingModelValidator thingModelValidator(DeviceDirectoryDao deviceDao, ObjectMapper objectMapper) {
        return new ThingModelValidator(deviceDao, objectMapper);
    }

    @Bean
    public TelemetryIngestService telemetryIngestService(TelemetryStore store, IngestDao ingestDao,
                                                         ThingModelValidator validator, ObjectMapper objectMapper,
                                                         MeterRegistry registry, IotProperties properties) {
        return new TelemetryIngestService(store, ingestDao, validator, objectMapper, registry,
                properties.getTelemetryBatchSize(), properties.getIngestQueueCapacity());
    }

    @Bean
    public TelemetryPartitionJob telemetryPartitionJob(JdbcTemplate jdbc, IotProperties properties) {
        return new TelemetryPartitionJob(jdbc, properties);
    }

    @Bean
    public IotMaintenanceJob iotMaintenanceJob(IngestDao ingestDao, DeviceDirectoryDao deviceDao,
                                               IotProperties properties, MeterRegistry registry) {
        return new IotMaintenanceJob(ingestDao, deviceDao, properties, registry);
    }

    @Bean
    public OutboxDao outboxDao(JdbcTemplate jdbc) {
        return new OutboxDao(jdbc);
    }

    @Bean
    public EventPublisher eventPublisher(OutboxDao outboxDao) {
        return new OutboxEventPublisher(outboxDao);
    }

    @Bean
    public StreamRelay streamRelay(StringRedisTemplate redisTemplate) {
        return new StreamRelay.RedisStreamRelay(redisTemplate);
    }

    @Bean
    public com.lrs.buddy.framework.event.OutboxDispatcher outboxDispatcher(OutboxDao outboxDao, StreamRelay relay,
                                                                          MeterRegistry registry) {
        return new com.lrs.buddy.framework.event.OutboxDispatcher(outboxDao, relay, registry);
    }
}
