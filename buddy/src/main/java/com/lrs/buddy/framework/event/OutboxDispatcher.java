package com.lrs.buddy.framework.event;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;

/**
 * Outbox 投递器。
 *
 * 三条实现要点：
 * 1 只扫 PENDING 且到期（走 (outbox_state, next_retry_at) 索引），不扫全表；
 * 2 状态更新带 CAS（markSent 要求仍是 PENDING），多实例并发不会重复投同一条；
 * 3 失败走指数退避并封顶，超过上限进 DEAD —— 无限重试会让一条坏数据占满投递带宽，
 *   把后面的正常事件全部堵住（队头阻塞）。DEAD 数进指标并告警，交人工裁决。
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxDispatcher {

    private final OutboxDao outboxDao;
    private final StreamRelay relay;
    private final MeterRegistry registry;

    @Scheduled(fixedDelayString = "${buddy.event.outbox-scan-interval-ms:3000}")
    @SchedulerLock(name = "outbox-dispatch", lockAtMostFor = "PT30S", lockAtLeastFor = "PT1S")
    public void dispatch() {
        registry.gauge("outbox.backlog", outboxDao, dao -> (double) dao.countPending());
        registry.gauge("outbox.dead", outboxDao, dao -> (double) dao.countDead());
        for (OutboxDao.Row row : outboxDao.scanDue(LocalDateTime.now(), 100)) {
            try {
                relay.publish(row);
                outboxDao.markSent(row.id(), LocalDateTime.now());
                registry.counter("outbox.dispatch", "result", "sent").increment();
            } catch (RuntimeException e) {
                outboxDao.scheduleRetry(row.id(), row.retryCount(), row.retryMax(), e.getMessage(),
                        LocalDateTime.now());
                registry.counter("outbox.dispatch", "result", "retry").increment();
                log.warn("Outbox 投递失败，退避重试：dedupKey={}, err={}", row.dedupKey(), e.getMessage());
            }
        }
    }
}
