package com.lrs.buddy.framework.iot.telemetry;

import com.lrs.buddy.framework.iot.config.IotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 遥测分区滚动维护。
 *
 * 为什么分区不写在 Flyway 迁移里：分区需要**持续**建新、淘汰过期，
 * 而迁移是一次性的。把一次性动作当成持续机制的起点，典型症状是第一个月正常、
 * 第二个月开始插入落到不存在的分区并报错 —— 那时改已经晚了。
 *
 * H2 不支持 PARTITION BY RANGE，因此按数据库产品判断，非 MySQL 直接跳过；
 * 分区的正确性由真库档用例断言 information_schema.PARTITIONS（swap-ddl.md §7）。
 */
@Slf4j
@RequiredArgsConstructor
public class TelemetryPartitionJob {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbc;
    private final IotProperties properties;

    @Scheduled(cron = "${buddy.iot.partition-cron:0 15 3 * * *}")
    @SchedulerLock(name = "iot-telemetry-partition", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void roll() {
        if (!properties.isEnabled() || !isMysql()) {
            log.debug("跳过分区维护（非 MySQL 或接入层关闭）");
            return;
        }
        LocalDate today = LocalDate.now();
        for (int offset = 0; offset <= 3; offset++) {
            createPartition(today.plusDays(offset));
        }
        LocalDate expireBefore = today.minusDays(Math.max(1, properties.getTelemetryRetentionDays()));
        dropOlderThan(expireBefore);
    }

    private void createPartition(LocalDate date) {
        String name = "p" + date.format(SUFFIX);
        String boundary = date.plusDays(1).atStartOfDay().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .replace('T', ' ');
        try {
            jdbc.update("ALTER TABLE iot_telemetry ADD PARTITION (PARTITION " + name
                    + " VALUES LESS THAN (TIMESTAMP '" + boundary + "'))");
            log.info("已创建遥测分区 {}", name);
        } catch (RuntimeException e) {
            // 已存在是常态（每天多次触发、多实例并发），不当作错误
            log.debug("创建分区 {} 跳过：{}", name, e.getMessage());
        }
    }

    private void dropOlderThan(LocalDate before) {
        String name = "p" + before.format(SUFFIX);
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.PARTITIONS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'iot_telemetry' AND PARTITION_NAME = ?",
                Integer.class, name);
        if (exists == null || exists == 0) {
            return;
        }
        // 删分区前先记行数：DROP PARTITION 是物理删除，不留痕迹，必须有可追溯的凭证
        Long rows = jdbc.queryForObject("SELECT TABLE_ROWS FROM information_schema.PARTITIONS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'iot_telemetry' AND PARTITION_NAME = ?",
                Long.class, name);
        log.warn("即将淘汰遥测分区 {}，行数约 {}", name, rows);
        jdbc.execute("ALTER TABLE iot_telemetry DROP PARTITION " + name);
    }

    private boolean isMysql() {
        Boolean mysql = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try {
                String product = connection.getMetaData().getDatabaseProductName();
                return product != null && product.toLowerCase().contains("mysql");
            } catch (Exception e) {
                return false;
            }
        });
        return Boolean.TRUE.equals(mysql);
    }
}
