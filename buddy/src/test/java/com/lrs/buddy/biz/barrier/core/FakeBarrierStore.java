package com.lrs.buddy.biz.barrier.core;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版存储（多杆），脱库单测引擎。默认：杆1 ← 策略1。
 *
 * <p>模拟乐观锁：写入成功则 version 自增；{@link #injectCasFailure} 可注入"跨实例并发写"——
 * 令接下来若干次 CAS 写抛 {@link OptimisticLockConflictException}（且不改状态、不记事件，模拟事务回滚），
 * 用以确定性验证引擎的让步(reconcile)/有界重试(manual)逻辑，无需真实线程或 DB。
 */
class FakeBarrierStore implements BarrierStore {

    final List<Long> barriers = new ArrayList<>(List.of(1L));
    final Map<Long, List<StrategySpec>> strategiesByBarrier = new HashMap<>();
    final Map<Long, List<SchedulePointSpec>> pointsByStrategy = new HashMap<>();
    final Map<Long, BarrierStatusView> statusByBarrier = new HashMap<>();
    final List<String> eventLog = new ArrayList<>();
    final List<Applied> applied = new ArrayList<>();
    private final Map<Long, Integer> casFailures = new HashMap<>();

    record Applied(Long barrierId, BarrierState state, TriggerSource source, Long operatorId) {
    }

    FakeBarrierStore() {
        bindStrategy(1L, 1L, 100); // 杆1 默认绑 策略1
    }

    void addBarrier(long barrierId) {
        barriers.add(barrierId);
    }

    void bindStrategy(long barrierId, long strategyId, int priority) {
        strategiesByBarrier.computeIfAbsent(barrierId, k -> new ArrayList<>())
                .add(new StrategySpec(strategyId, "s" + strategyId, priority, 1));
    }

    void setPoints(long strategyId, List<SchedulePointSpec> points) {
        pointsByStrategy.put(strategyId, points);
    }

    /** 注入 count 次"跨实例并发写冲突"：接下来 count 次对该杆的 CAS 写将失败（不改状态、不记事件）。 */
    void injectCasFailure(long barrierId, int count) {
        casFailures.put(barrierId, count);
    }

    BarrierStatusView st(long barrierId) {
        return statusByBarrier.getOrDefault(barrierId, new BarrierStatusView(BarrierState.CLOSED, false, null, null));
    }

    /** 消费一次注入的冲突；若仍有额度则抛异常（模拟 CAS 失败 + 事务回滚）。 */
    private void consumeInjectedFailure(long barrierId) {
        int remaining = casFailures.getOrDefault(barrierId, 0);
        if (remaining > 0) {
            casFailures.put(barrierId, remaining - 1);
            throw new OptimisticLockConflictException(
                    "注入的跨实例写冲突（杆" + barrierId + "，剩余" + (remaining - 1) + "）");
        }
    }

    /** 写入成功后 version 自增（null 视为 0 起步），模拟 DB 乐观锁版本推进。 */
    private BarrierStatusView bumped(BarrierStatusView v) {
        long next = (v.version() == null ? 0L : v.version()) + 1;
        return new BarrierStatusView(v.state(), v.manualOverride(), v.lastScheduled(), next);
    }

    @Override
    public List<Long> enabledBarrierIds() {
        return barriers;
    }

    @Override
    public List<StrategySpec> enabledStrategiesBoundTo(Long barrierId) {
        return strategiesByBarrier.getOrDefault(barrierId, List.of());
    }

    @Override
    public List<SchedulePointSpec> enabledPointsOf(Long strategyId) {
        return pointsByStrategy.getOrDefault(strategyId, List.of());
    }

    @Override
    public BarrierStatusView loadStatus(Long barrierId) {
        return st(barrierId);
    }

    @Override
    public void saveStatus(Long barrierId, BarrierStatusView view) {
        consumeInjectedFailure(barrierId);
        statusByBarrier.put(barrierId, bumped(view));
    }

    @Override
    public void apply(Long barrierId, BarrierStatusView newStatus, BarrierState state, TriggerSource source,
                      LocalDateTime occurredAt, String message, Long operatorId) {
        consumeInjectedFailure(barrierId);
        statusByBarrier.put(barrierId, bumped(newStatus));
        eventLog.add(barrierId + ":" + state + "/" + source);
        applied.add(new Applied(barrierId, state, source, operatorId));
    }
}
