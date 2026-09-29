package com.lrs.buddy.biz.barrier.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 调度引擎（多杆）：遍历启用杆，每根杆独立 reconcile/manual。
 *
 * <p><b>并发模型（三层协同）</b>：
 * <ol>
 *   <li>进程内：每根杆一把 {@link ReentrantLock}（分段锁，决策B）——同杆串行、异杆并行，reconcile 逐杆持锁不整轮独占。</li>
 *   <li>跨实例心跳：ShedLock 保证 @Scheduled reconcile 集群内单实例执行（见 ShedLockConfig）。</li>
 *   <li>跨实例写：{@code @Version} 乐观锁 CAS——store 落库带读取时版本，冲突抛 {@link OptimisticLockConflictException}。
 *       reconcile/alignOnStartup 冲突则本次让步、下次心跳重读自愈（幂等）；manual 冲突则有界重试，耗尽则上抛交 web 层翻译。</li>
 * </ol>
 */
public class BarrierEngine {

    private static final Logger log = LoggerFactory.getLogger(BarrierEngine.class);

    /** manual 遇跨实例 CAS 冲突时的最大尝试次数（含首次）。 */
    private static final int MAX_CAS_ATTEMPTS = 3;

    private final BarrierStore store;
    private final ZoneId zone;
    private final ConcurrentMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public BarrierEngine(BarrierStore store, ZoneId zone) {
        this.store = store;
        this.zone = zone;
    }

    private ReentrantLock lockFor(Long barrierId) {
        return locks.computeIfAbsent(barrierId, k -> new ReentrantLock());
    }

    /** 定时心跳：逐杆对齐到各自生效策略此刻的目标态。 */
    public void reconcile(ZonedDateTime now) {
        int minute = minuteOf(now);
        for (Long barrierId : store.enabledBarrierIds()) {
            ReentrantLock lock = lockFor(barrierId);
            lock.lock();
            try {
                reconcileOne(barrierId, minute, now);
            } finally {
                lock.unlock();
            }
        }
    }

    private void reconcileOne(Long barrierId, int minute, ZonedDateTime now) {
        StrategySpec active = StrategyResolver.pickActive(store.enabledStrategiesBoundTo(barrierId)).orElse(null);
        if (active == null) {
            return;
        }
        BarrierState target = ScheduleComputer.scheduledStateAt(minute, store.enabledPointsOf(active.id()));
        if (target == null) {
            return;
        }
        BarrierStatusView s = store.loadStatus(barrierId);
        boolean crossed = (s.lastScheduled() == null) || (s.lastScheduled() != target);
        try {
            if (crossed) {
                if (s.state() != target) {
                    store.apply(barrierId, new BarrierStatusView(target, false, target, s.version()), target,
                            TriggerSource.SCHEDULED, now.toLocalDateTime(), "定时切换", null);
                } else {
                    store.saveStatus(barrierId, new BarrierStatusView(target, false, target, s.version()));
                }
                return;
            }
            if (!s.manualOverride() && s.state() != target) {
                store.apply(barrierId, new BarrierStatusView(target, false, s.lastScheduled(), s.version()), target,
                        TriggerSource.SCHEDULED, now.toLocalDateTime(), "定时对齐", null);
            }
        } catch (OptimisticLockConflictException e) {
            // 跨实例并发写（多半是别处的手动）胜出：本次 tick 对该杆让步，下次心跳重读最新态自愈（reconcile 幂等）。
            log.debug("杆[{}]定时对齐遇并发写冲突，本次让步，待下次心跳重算：{}", barrierId, e.getMessage());
        }
    }

    /** 手动指令（指定杆）：最后写入者胜，立即生效、标记 override、记操作人；跨实例 CAS 冲突则有界重试。 */
    public void manual(Long barrierId, BarrierState target, ZonedDateTime now, Long operatorId) {
        ReentrantLock lock = lockFor(barrierId);
        lock.lock();
        try {
            for (int attempt = 1; ; attempt++) {
                BarrierStatusView s = store.loadStatus(barrierId);
                if (s.state() == target && s.manualOverride()) {
                    return; // 幂等
                }
                try {
                    store.apply(barrierId, new BarrierStatusView(target, true, s.lastScheduled(), s.version()), target,
                            TriggerSource.MANUAL, now.toLocalDateTime(), "手动操作", operatorId);
                    return;
                } catch (OptimisticLockConflictException e) {
                    if (attempt >= MAX_CAS_ATTEMPTS) {
                        throw e; // 重试耗尽：交上层（控制器 → GlobalExceptionHandler）映射为友好冲突响应
                    }
                    log.debug("杆[{}]手动操作遇并发写冲突，重读重试({}/{})", barrierId, attempt, MAX_CAS_ATTEMPTS);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** 启动对齐：逐杆按当前时间对齐生效策略，清 override、设 lastScheduled。 */
    public void alignOnStartup(ZonedDateTime now) {
        int minute = minuteOf(now);
        for (Long barrierId : store.enabledBarrierIds()) {
            ReentrantLock lock = lockFor(barrierId);
            lock.lock();
            try {
                StrategySpec active = StrategyResolver.pickActive(store.enabledStrategiesBoundTo(barrierId)).orElse(null);
                if (active == null) {
                    continue;
                }
                BarrierState target = ScheduleComputer.scheduledStateAt(minute, store.enabledPointsOf(active.id()));
                if (target == null) {
                    log.warn("启动对齐：杆[{}]生效策略[{}]无启用时间点，跳过", barrierId, active.name());
                    continue;
                }
                BarrierStatusView s = store.loadStatus(barrierId);
                try {
                    store.saveStatus(barrierId, new BarrierStatusView(target, false, target, s.version()));
                } catch (OptimisticLockConflictException e) {
                    log.debug("杆[{}]启动对齐遇并发写冲突，让步：{}", barrierId, e.getMessage());
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private int minuteOf(ZonedDateTime now) {
        ZonedDateTime z = now.withZoneSameInstant(zone);
        return z.getHour() * 60 + z.getMinute();
    }
}
