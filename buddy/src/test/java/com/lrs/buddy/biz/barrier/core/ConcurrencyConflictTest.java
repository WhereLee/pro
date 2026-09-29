package com.lrs.buddy.biz.barrier.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跨实例并发写冲突（{@code @Version} CAS 失败）下引擎的行为：
 * reconcile 让步且不留孤儿事件、下次心跳自愈；manual 有界重试后成功；重试耗尽则上抛。
 * 用 {@link FakeBarrierStore#injectCasFailure} 确定性注入冲突，不依赖真实线程/DB。
 */
class ConcurrencyConflictTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long B1 = 1L;

    private static ZonedDateTime at(int h, int m) {
        return ZonedDateTime.of(2026, 1, 1, h, m, 0, 0, ZONE);
    }

    private static FakeBarrierStore storeWithDailyPoints() {
        FakeBarrierStore s = new FakeBarrierStore();
        s.setPoints(1L, new ArrayList<>(List.of(
                SchedulePointSpec.of(LocalTime.of(8, 0), BarrierState.OPEN),
                SchedulePointSpec.of(LocalTime.of(20, 0), BarrierState.CLOSED))));
        return s;
    }

    @Test
    @DisplayName("reconcile 遇跨实例写冲突 → 让步不抛、不留孤儿事件；下次心跳自愈")
    void reconcileYieldsOnConflictThenSelfHeals() {
        FakeBarrierStore store = storeWithDailyPoints();
        BarrierEngine engine = new BarrierEngine(store, ZONE);
        engine.alignOnStartup(at(7, 0));                 // CLOSED，saveStatus 不记事件
        assertThat(store.eventLog).isEmpty();

        store.injectCasFailure(B1, 1);                    // 下一次写将冲突（模拟别的实例先写了）
        engine.reconcile(at(8, 1));                       // 跨 08:00 → 本应 OPEN，但 CAS 冲突 → 让步
        assertThat(store.st(B1).state()).isEqualTo(BarrierState.CLOSED);  // 未改
        assertThat(store.eventLog).isEmpty();             // 无孤儿事件

        engine.reconcile(at(8, 2));                       // 冲突额度已耗尽 → 本次成功自愈
        assertThat(store.st(B1).state()).isEqualTo(BarrierState.OPEN);
        assertThat(store.eventLog).hasSize(1);
    }

    @Test
    @DisplayName("manual 遇跨实例写冲突 → 有界重试后成功，且只记一条事件")
    void manualRetriesThenSucceeds() {
        FakeBarrierStore store = storeWithDailyPoints();
        BarrierEngine engine = new BarrierEngine(store, ZONE);
        engine.alignOnStartup(at(9, 0));                 // OPEN
        int eventsBefore = store.eventLog.size();

        store.injectCasFailure(B1, 1);                    // 首次 CAS 冲突，重试应成功
        engine.manual(B1, BarrierState.CLOSED, at(10, 0), 7L);

        assertThat(store.st(B1).state()).isEqualTo(BarrierState.CLOSED);
        assertThat(store.st(B1).manualOverride()).isTrue();
        assertThat(store.eventLog).hasSize(eventsBefore + 1);   // 冲突那次已回滚，只 +1
    }

    @Test
    @DisplayName("manual 冲突持续（重试耗尽）→ 上抛 OptimisticLockConflictException，不留孤儿事件")
    void manualThrowsWhenRetriesExhausted() {
        FakeBarrierStore store = storeWithDailyPoints();
        BarrierEngine engine = new BarrierEngine(store, ZONE);
        engine.alignOnStartup(at(9, 0));
        int eventsBefore = store.eventLog.size();

        store.injectCasFailure(B1, 99);                   // 始终冲突 → 重试耗尽
        assertThatThrownBy(() -> engine.manual(B1, BarrierState.CLOSED, at(10, 0), 7L))
                .isInstanceOf(OptimisticLockConflictException.class);
        assertThat(store.st(B1).state()).isEqualTo(BarrierState.OPEN);   // 未改
        assertThat(store.eventLog).hasSize(eventsBefore);                // 无孤儿事件
    }
}
