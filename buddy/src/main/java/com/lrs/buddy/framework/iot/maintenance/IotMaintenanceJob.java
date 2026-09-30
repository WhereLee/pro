package com.lrs.buddy.framework.iot.maintenance;

import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.repo.IngestDao;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 接入层日常维护：去重表过期清理 + 阈值冗余漂移校验。
 *
 * 漂移为什么要专门校验：站点表冗余了一份型号默认值，是为了让"只能收紧"能用同表 CHECK 表达
 * （CHECK 不能跨表）。冗余一旦漂移，CHECK 就会拿旧值判定 —— 约束看起来还在，实际保护的是过期的规则。
 * 这种"约束还在但依据已过期"的状态最危险，因为没有任何报错会提醒它。
 */
@Slf4j
@RequiredArgsConstructor
public class IotMaintenanceJob {

    private final IngestDao ingestDao;
    private final DeviceDirectoryDao deviceDao;
    private final IotProperties properties;
    private final MeterRegistry registry;

    @Scheduled(cron = "${buddy.iot.cleanup-cron:0 45 3 * * *}")
    @SchedulerLock(name = "iot-cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void cleanup() {
        int removed = ingestDao.deleteDedupExpired(LocalDateTime.now().minusDays(properties.getDedupRetentionDays()));
        if (removed > 0) {
            log.info("清理过期去重记录 {} 条", removed);
        }
    }

    @Scheduled(fixedDelayString = "${buddy.iot.drift-check-interval-ms:600000}")
    @SchedulerLock(name = "iot-threshold-drift", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void checkThresholdDrift() {
        List<Map<String, Object>> rows = deviceDao.thresholdDriftRows();
        registry.gauge("iot.threshold.drift", rows, List::size);
        if (!rows.isEmpty()) {
            log.warn("站点阈值冗余副本已漂移，共 {} 个站点需要刷新：{}", rows.size(), rows);
        }
    }
}
