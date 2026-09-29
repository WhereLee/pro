package com.lrs.buddy.framework.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ShedLock：多实例部署时，保证 {@code @Scheduled} 心跳（reconcile）在集群内"同一时刻至多一个实例执行"。
 *
 * <p>用 JDBC LockProvider（锁记录落 {@code shedlock} 表，Flyway V4 建）——与业务同库，无需额外中间件（Redis/ZK）。
 * {@code usingDbTime()} 用数据库时间而非各实例本地时钟判定锁，规避多机时钟漂移导致的锁误判。
 *
 * <p>语义边界：ShedLock 只保证"至多一个实例执行"，不保证"恰好一个"（lockAtLeastFor 窗口内不重复触发）。
 * reconcile 本身幂等；跨实例写安全由三层共同兜底：ShedLock（心跳单例）+ 引擎按杆 ReentrantLock（进程内串行）
 * + {@code @Version} CAS（DB 层跨实例写冲突检测，见块7c）。
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
