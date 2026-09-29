package com.lrs.buddy.biz.barrier.infrastructure;

import com.lrs.buddy.biz.barrier.core.BarrierState;
import com.lrs.buddy.biz.barrier.core.TriggerSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 领域指标埋点（适配器层）——核心引擎保持无 Micrometer 依赖、可脱库单测，指标只在 store/scheduler 边界采集。
 *
 * <p>指标：
 * <ul>
 *   <li>{@code barrier.reconcile}（Timer）：心跳 reconcile 耗时/次数。</li>
 *   <li>{@code barrier.apply}（Counter, tag source=SCHEDULED|MANUAL）：成功落库的状态切换数。</li>
 *   <li>{@code barrier.cas.conflict}（Counter, tag reason=version|duplicate-insert）：跨实例 CAS 冲突数——三层并发模型的真实压力信号。</li>
 *   <li>{@code barrier.state}（Gauge, tag barrierId）：每杆当前态 0=CLOSED/1=OPEN，内存态、Prometheus 抓取不打库。</li>
 * </ul>
 */
@Component
public class BarrierMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<Long, AtomicInteger> stateGauges = new ConcurrentHashMap<>();

    public BarrierMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 环绕一次 reconcile，记录耗时与次数。 */
    public void timeReconcile(Runnable reconcile) {
        registry.timer("barrier.reconcile").record(reconcile);
    }

    /** 一次成功的状态切换（含事件）。 */
    public void recordApply(TriggerSource source) {
        registry.counter("barrier.apply", "source", source.name()).increment();
    }

    /** 一次跨实例 CAS 冲突（reason=version 版本不符 / duplicate-insert 并发插入撞唯一索引）。 */
    public void recordConflict(String reason) {
        registry.counter("barrier.cas.conflict", "reason", reason).increment();
    }

    /** 更新某杆当前态 gauge（首次写该杆时惰性注册 gauge，支持运行期新增杆）。 */
    public void updateState(Long barrierId, BarrierState state) {
        AtomicInteger holder = stateGauges.computeIfAbsent(barrierId, id -> {
            AtomicInteger ai = new AtomicInteger(0);
            Gauge.builder("barrier.state", ai, AtomicInteger::get)
                    .tag("barrierId", id.toString())
                    .description("杆当前状态：0=CLOSED,1=OPEN")
                    .register(registry);
            return ai;
        });
        holder.set(state == BarrierState.OPEN ? 1 : 0);
    }
}
