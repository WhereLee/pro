package com.lrs.buddy.biz.barrier.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 多杆调度：各杆按自己绑定的策略独立收敛；一杆多策略取最高优先级；一策略绑多杆同时驱动。 */
class MultiBarrierEngineTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static ZonedDateTime at(int h, int m) {
        return ZonedDateTime.of(2026, 1, 1, h, m, 0, 0, ZONE);
    }

    @Test
    @DisplayName("两根杆绑不同策略 → 各自独立收敛到不同状态")
    void twoBarriersIndependent() {
        FakeBarrierStore store = new FakeBarrierStore();     // 杆1 ← 策略1
        store.addBarrier(2L);
        store.bindStrategy(2L, 2L, 100);                      // 杆2 ← 策略2
        store.setPoints(1L, List.of(SchedulePointSpec.of(LocalTime.of(8, 0), BarrierState.OPEN)));
        store.setPoints(2L, List.of(SchedulePointSpec.of(LocalTime.of(8, 0), BarrierState.CLOSED)));

        new BarrierEngine(store, ZONE).alignOnStartup(at(9, 0));

        assertThat(store.st(1L).state()).isEqualTo(BarrierState.OPEN);
        assertThat(store.st(2L).state()).isEqualTo(BarrierState.CLOSED);
    }

    @Test
    @DisplayName("一根杆绑两个策略 → 取优先级最高的生效")
    void highestPriorityStrategyWins() {
        FakeBarrierStore store = new FakeBarrierStore();     // 杆1 ← 策略1(优先级100)
        store.bindStrategy(1L, 2L, 200);                      // 杆1 再绑 策略2(优先级200)
        store.setPoints(1L, List.of(SchedulePointSpec.of(LocalTime.of(8, 0), BarrierState.OPEN)));
        store.setPoints(2L, List.of(SchedulePointSpec.of(LocalTime.of(8, 0), BarrierState.CLOSED)));

        new BarrierEngine(store, ZONE).alignOnStartup(at(9, 0));

        // 生效的是策略2 → CLOSED（而非策略1 的 OPEN）
        assertThat(store.st(1L).state()).isEqualTo(BarrierState.CLOSED);
    }

    @Test
    @DisplayName("一个策略绑两根杆 → 两杆同时被驱动到同一目标态")
    void oneStrategyDrivesTwoBarriers() {
        FakeBarrierStore store = new FakeBarrierStore();     // 杆1 ← 策略1
        store.addBarrier(2L);
        store.bindStrategy(2L, 1L, 100);                      // 杆2 也 ← 策略1
        store.setPoints(1L, List.of(SchedulePointSpec.of(LocalTime.of(8, 0), BarrierState.OPEN)));

        BarrierEngine engine = new BarrierEngine(store, ZONE);
        engine.alignOnStartup(at(7, 0));
        engine.reconcile(at(8, 1));                          // 跨过 08:00 → 两杆都 OPEN

        assertThat(store.st(1L).state()).isEqualTo(BarrierState.OPEN);
        assertThat(store.st(2L).state()).isEqualTo(BarrierState.OPEN);
    }

    @Test
    @DisplayName("手动只影响指定杆，不波及其它杆")
    void manualIsPerBarrier() {
        FakeBarrierStore store = new FakeBarrierStore();
        store.addBarrier(2L);
        store.bindStrategy(2L, 1L, 100);
        store.setPoints(1L, List.of(SchedulePointSpec.of(LocalTime.of(8, 0), BarrierState.OPEN)));

        BarrierEngine engine = new BarrierEngine(store, ZONE);
        engine.alignOnStartup(at(9, 0));                     // 两杆 OPEN
        engine.manual(1L, BarrierState.CLOSED, at(10, 0), 7L); // 只关卡1

        assertThat(store.st(1L).state()).isEqualTo(BarrierState.CLOSED);
        assertThat(store.st(1L).manualOverride()).isTrue();
        assertThat(store.st(2L).state()).isEqualTo(BarrierState.OPEN);
        assertThat(store.st(2L).manualOverride()).isFalse();
    }
}
