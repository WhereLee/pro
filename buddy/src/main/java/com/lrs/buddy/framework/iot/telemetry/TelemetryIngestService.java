package com.lrs.buddy.framework.iot.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrs.buddy.framework.iot.envelope.Envelope;
import com.lrs.buddy.framework.iot.model.ThingModelValidator;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.repo.IngestDao;
import com.lrs.buddy.framework.iot.transport.InboundRouter;
import com.lrs.buddy.framework.iot.transport.MqttTopics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 遥测入库：先聚合再批量落库，且**任何失败都不回传到接收线程**。
 *
 * 为什么必须有界（maxBuffered）而不是无界队列：
 * 设备上报速率是外部可控的，数据库写慢时队列会无上限吃内存 —— 一次数据库抖动就能让整机 OOM。
 * 溢出时的正确做法是丢最旧的并计数告警：遥测本来就是 QoS0 可丢（协议 §2.2），
 * 而"丢了看得见"比"不丢但把服务拖死"更符合生产要求。
 */
@Slf4j
@RequiredArgsConstructor
public class TelemetryIngestService implements InboundRouter.InboundListener {

    private final TelemetryStore store;
    private final IngestDao ingestDao;
    private final ThingModelValidator validator;
    private final ObjectMapper objectMapper;
    private final MeterRegistry registry;
    private final int batchSize;
    private final int maxBuffered;

    private final ConcurrentLinkedQueue<TelemetryStore.TelemetryRecord> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicLong buffered = new AtomicLong();

    @Override
    public boolean supports(MqttTopics.Kind kind) {
        return kind == MqttTopics.Kind.TELEMETRY;
    }

    @Override
    public void onInbound(MqttTopics.Inbound topic, Envelope envelope, DeviceDirectoryDao.Device device) {
        JsonNode data = envelope.data();
        List<ThingModelValidator.Violation> violations = validator.validate(topic.productKey(), data);
        if (!violations.isEmpty()) {
            // 不合规模型：原文入 raw_payload，主链路不阻塞（协议 §11.4）
            registry.counter("telemetry.reject").increment();
            ingestDao.insertRawPayload(System.nanoTime(), device.id(), device.productKey(), envelope.msgId(),
                    topic.kind().name(), text(data), violations.get(0).reason(), violations.toString(),
                    LocalDateTime.now(), device.tenantId());
            return;
        }
        if (buffered.get() >= maxBuffered) {
            registry.counter("telemetry.drop", "reason", "buffer_full").increment();
            return;
        }
        buffer.add(new TelemetryStore.TelemetryRecord(device.id(), device.productKey(),
                metricKind(topic.kind()), text(data), ts(envelope), envelope.seq(), device.tenantId()));
        buffered.incrementAndGet();
        registry.gauge("telemetry.buffered", buffered, AtomicLong::doubleValue);
    }

    @Scheduled(fixedDelayString = "${buddy.iot.telemetry-flush-interval-ms:1000}")
    public void flush() {
        List<TelemetryStore.TelemetryRecord> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            TelemetryStore.TelemetryRecord record = buffer.poll();
            if (record == null) {
                break;
            }
            buffered.decrementAndGet();
            batch.add(record);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            store.saveBatch(batch);
            registry.counter("telemetry.written").increment(batch.size());
        } catch (RuntimeException e) {
            // 只计数与告警，绝不重试到无限堆积：遥测的价值在趋势，不在每一行
            registry.counter("telemetry.drop", "reason", "store_error").increment(batch.size());
            log.warn("遥测批量写入失败，丢弃 {} 条：{}", batch.size(), e.getMessage());
        }
    }

    private static String metricKind(MqttTopics.Kind kind) {
        return kind == MqttTopics.Kind.TELEMETRY ? "CABINET" : "ENV";
    }

    private static long ts(Envelope envelope) {
        return envelope.issuedAt() == null ? System.currentTimeMillis() : envelope.issuedAt();
    }

    private String text(JsonNode node) {
        try {
            return node == null ? "{}" : objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return new String(String.valueOf(node).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
    }
}
