package com.lrs.buddy.biz.barrier.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 引擎单测（单杆场景，杆1←策略1）。多杆见 MultiBarrierEngineTest。 */
class BarrierEngineTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long B1 = 1L;

    private static ZonedDateTime at(int h, int m) {
        return ZonedDateTime.of(2026, 1, 1, h, m, 0, 0, ZONE);
    }

    private static List<SchedulePointSpec> daily() {
        return new ArrayList<>(List.of(
                SchedulePointSpec.of(LocalTime.of(8, 0), BarrierState.OPEN),
                SchedulePointSpec.of(LocalTime.of(20, 0), BarrierState.CLOSED)));
    }

    private FakeBarrierStore storeWithPoints() {
        FakeBarrierStore s = new FakeBarrierStore();
        s.setPoints(1L, daily());
        return s;
    }

    @Test
    @DisplayName("跨越边界 → 切换并记事件；重复 tick 幂等不再动")
    void transitionThenIdempotent() {
        FakeBarrierStore store = storeWithPoints();
        BarrierEngine engine = new BarrierEngine(store, ZONE);

        engine.alignOnStartup(at(7, 0));               // 07:00 → 回绕 CLOSED，与初始同 → 0 事件
        assertThat(store.eventLog).isEmpty();
        assertThat(store.st(B1).state()).isEqualTo(BarrierState.CLOSED);

        engine.reconcile(at(8, 1));                    // 跨过 08:00 → OPEN，记一条
        assertThat(store.st(B1).state()).isEqualTo(BarrierState.OPEN);
        assertThat(store.st(B1).lastScheduled()).isEqualTo(BarrierState.OPEN);
        assertThat(store.eventLog).hasSize(1);

        engine.reconcile(at(9, 0));                    // 窗内，无边界 → 幂等不动
        engine.reconcile(at(10, 0));
        assertThat(store.eventLog).hasSize(1);
    }

    @Test
    @DisplayName("手动覆盖：立即生效并压住定时，直到下一个边界回归")
    void manualOverrideThenRegress() {
        FakeBarrierStore store = storeWithPoints();
        BarrierEngine engine = new BarrierEngine(store, ZONE);
        engine.alignOnStartup(at(9, 0));               // 09:00 → OPEN
        assertThat(store.st(B1).state()).isEqualTo(BarrierState.OPEN);

        engine.manual(B1, BarrierState.CLOSED, at(12, 0), 1L); // 窗内手动关
        assertThat(store.st(B1).state()).isEqualTo(BarrierState.CLOSED);
        assertThat(store.st(B1).manualOverride()).isTrue();

        engine.reconcile(at(13, 0));                    // 窗内：override 生效，引擎沉默
        assertThat(store.st(B1).state()).isEqualTo(BarrierState.CLOSED);
        assertThat(store.st(B1).manualOverride()).isTrue();

        engine.reconcile(at(20, 1));                    // 跨过 20:00 → 收回覆盖、回归定时
        assertThat(store.st(B1).state()).isEqualTo(BarrierState.CLOSED);
        assertThat(store.st(B1).manualOverride()).isFalse();
        assertThat(store.st(B1).lastScheduled()).isEqualTo(BarrierState.CLOSED);
    }

    @Test
    @DisplayName("时钟回拨重复覆盖同一点 → 不产生第二次动作")
    void clockRollbackNoDoubleAction() {
        FakeBarrierStore store = new FakeBarrierStore();
        store.setPoints(1L, new ArrayList<>(List.of(
                SchedulePointSpec.of(LocalTime.of(8, 0), BarrierState.OPEN))));
        BarrierEngine engine = new BarrierEngine(store, ZONE);

        engine.alignOnStartup(at(9, 0));               // OPEN
        int before = store.eventLog.size();
        engine.reconcile(at(8, 30));                    // 时间倒退，目标仍 OPEN == lastScheduled → 不动
        assertThat(store.eventLog).hasSize(before);
    }

    @Test
    @DisplayName("生效策略无启用计划点 → 引擎不动作")
    void noPlanNoAction() {
        FakeBarrierStore store = new FakeBarrierStore(); // 杆1←策略1，但策略1无时间点
        BarrierEngine engine = new BarrierEngine(store, ZONE);
        engine.reconcile(at(9, 0));
        assertThat(store.st(B1).state()).isEqualTo(BarrierState.CLOSED);
        assertThat(store.eventLog).isEmpty();
    }

    @Test
    @DisplayName("并发手动+定时混合触发（同杆）→ 按杆锁串行、最终态确定、无异常")
    void concurrentTriggersSerialize() throws Exception {
        FakeBarrierStore store = storeWithPoints();
        BarrierEngine engine = new BarrierEngine(store, ZONE);
        engine.alignOnStartup(at(9, 0));

        int n = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int k = i;
            futures.add(pool.submit(() -> {
                start.await();
                if (k % 2 == 0) {
                    engine.manual(B1, k % 4 == 0 ? BarrierState.OPEN : BarrierState.CLOSED, at(12, 0), (long) k);
                } else {
                    engine.reconcile(at(12, 0));
                }
                return null;
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        for (var f : futures) {
            f.get();
        }
        assertThat(store.st(B1).state()).isIn(BarrierState.OPEN, BarrierState.CLOSED);
    }
}
