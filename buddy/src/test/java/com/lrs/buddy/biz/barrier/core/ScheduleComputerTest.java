package com.lrs.buddy.biz.barrier.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleComputerTest {

    private static SchedulePointSpec at(String hhmm, BarrierState s) {
        return SchedulePointSpec.of(LocalTime.parse(hhmm), s);
    }

    private static int min(int h, int m) {
        return h * 60 + m;
    }

    @Test
    @DisplayName("取 ≤ now 的最大点")
    void picksLatestPassed() {
        List<SchedulePointSpec> pts = List.of(at("08:00", BarrierState.OPEN), at("20:00", BarrierState.CLOSED));
        assertThat(ScheduleComputer.scheduledStateAt(min(12, 0), pts)).isEqualTo(BarrierState.OPEN);
        assertThat(ScheduleComputer.scheduledStateAt(min(21, 0), pts)).isEqualTo(BarrierState.CLOSED);
    }

    @Test
    @DisplayName("早于当天首个点 → 回绕到昨天最后动作（跨午夜）")
    void wrapsBeforeFirstPoint() {
        List<SchedulePointSpec> pts = List.of(at("08:00", BarrierState.OPEN), at("20:00", BarrierState.CLOSED));
        // 06:00 还没到 08:00 → 取全天最后(20:00 CLOSED)
        assertThat(ScheduleComputer.scheduledStateAt(min(6, 0), pts)).isEqualTo(BarrierState.CLOSED);
    }

    @Test
    @DisplayName("边界时刻自身算“已到”（<=）")
    void boundaryInclusive() {
        List<SchedulePointSpec> pts = List.of(at("08:00", BarrierState.OPEN));
        assertThat(ScheduleComputer.scheduledStateAt(min(8, 0), pts)).isEqualTo(BarrierState.OPEN);
    }

    @Test
    @DisplayName("无启用点 → null（不动）")
    void noPoints() {
        assertThat(ScheduleComputer.scheduledStateAt(min(9, 0), List.of())).isNull();
        assertThat(ScheduleComputer.scheduledStateAt(min(9, 0), null)).isNull();
    }
}
