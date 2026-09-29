package com.lrs.buddy.biz.barrier.scheduler;

import com.lrs.buddy.biz.barrier.core.BarrierEngine;
import com.lrs.buddy.biz.barrier.infrastructure.BarrierMetrics;
import com.lrs.buddy.framework.tenant.IgnoreTenant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 心跳：周期性触发对齐。决策是分钟粒度，tick 更细只为降低"到点后延迟"；
 * 即便某个 tick 漏跑，下一次 {@code reconcile} 依"当前时间"重算 → 天然补回错过的边界。
 *
 * <p>{@code barrier.scheduler.enabled=false} 时不装配（测试里手动驱动引擎，保证确定性）。
 */
@Component
@ConditionalOnProperty(name = "barrier.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class BarrierScheduler {

    private final BarrierEngine engine;
    private final BarrierMetrics metrics;
    private final ZoneId zone;

    public BarrierScheduler(BarrierEngine engine, BarrierMetrics metrics,
                            @Value("${barrier.zone:Asia/Shanghai}") String zone) {
        this.engine = engine;
        this.metrics = metrics;
        this.zone = ZoneId.of(zone);
    }

    // @SchedulerLock：多实例部署时集群内同一时刻只有一个实例执行本次 tick（锁记录在 shedlock 表）。
    // lockAtMostFor=30s：持锁实例崩溃后锁最多 30s 自动释放（须 > 单次 reconcile 最长耗时，reconcile 很快，30s 冗余充足）；
    // lockAtLeastFor=2s：即使 reconcile 瞬间完成也至少持锁 2s，避免同窗口被别的实例重复触发（< tick 5s，不会漏拍）。
    // @IgnoreTenant：调度心跳是系统级任务，需跨租户对齐所有杆（多租户开启时不被 tenant_id 过滤）。
    @IgnoreTenant
    @Scheduled(fixedDelayString = "${barrier.scheduler.tick-ms:5000}")
    @SchedulerLock(name = "barrier-reconcile", lockAtMostFor = "PT30S", lockAtLeastFor = "PT2S")
    public void tick() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        metrics.timeReconcile(() -> engine.reconcile(now));
    }
}
