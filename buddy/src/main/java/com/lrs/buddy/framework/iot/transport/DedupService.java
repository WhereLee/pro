package com.lrs.buddy.framework.iot.transport;

import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.repo.IngestDao;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

/**
 * 上行去重（协议 §6 第 8 步、§11.1）。
 *
 * 为什么必须落数据库唯一索引而不是 Redis SETNX：
 * Redis 丢数据（重启、驱逐、主从切换）会让同一条 QoS1 报文被二次分发，
 * 而二次分发的后果是重复扣权益 —— 那是资金级错误。
 * Redis 可以做前置加速，但判定权必须在数据库；这里先用 DB 单一手段。
 */
@Slf4j
public class DedupService {

    private final IngestDao ingestDao;
    private final IotProperties properties;
    private final MeterRegistry registry;

    public DedupService(IngestDao ingestDao, IotProperties properties, MeterRegistry registry) {
        this.ingestDao = ingestDao;
        this.properties = properties;
        this.registry = registry;
    }

    /** @return true 表示首次出现，可继续分发；false 表示重复，必须停止分发 */
    public boolean firstSeen(Long deviceId, String msgId, String topic, String eventType, LocalDateTime receivedAt) {
        LocalDateTime expireAt = receivedAt.plusDays(properties.getDedupRetentionDays());
        try {
            boolean first = ingestDao.markFirstSeen(deviceId, msgId, topic, eventType, receivedAt, expireAt, 1L);
            if (!first) {
                registry.counter("iot.event.duplicate").increment();
            }
            return first;
        } catch (RuntimeException e) {
            // 去重存储故障时保守停止分发：宁可丢一次分发（设备会重投），不可重复生效
            log.error("去重写入异常，按重复处理以阻止重复生效：deviceId={}, msgId={}, err={}",
                    deviceId, msgId, e.getMessage());
            return false;
        }
    }
}
