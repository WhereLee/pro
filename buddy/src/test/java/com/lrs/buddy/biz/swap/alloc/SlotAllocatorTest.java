package com.lrs.buddy.biz.swap.alloc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分配策略逐档断言（swap-order-fsm §12.1 的实现证据）。
 *
 * 写成纯函数单测的好处正在这里：每一档优先级都能单独构造"只有这一档不同"的两个候选，
 * 从而证明**是这一档在起作用**，而不是一堆数据凑出来的结果。
 * 如果只做集成测试，"温度低优先"这种断言永远可能被 SOC、SOH、仓号顺序同时干扰而无法归因。
 */
class SlotAllocatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0);
    private static final SlotAllocator.Thresholds LIMITS =
            new SlotAllocator.Thresholds(80, new BigDecimal("45.0"), Duration.ofMinutes(5), NOW);

    private static SlotAllocator.Candidate candidate(int slotNo, String batteryCode, String slotState,
                                                     String batteryState, String locationState, Integer soc,
                                                     int soh, int cycles, String temp, String lastFullMinutesAgo,
                                                     String faultCode, Integer disabledFlag) {
        return new SlotAllocator.Candidate(slotNo, slotState, batteryCode, batteryState, locationState, soc,
                new BigDecimal(soh), cycles, new BigDecimal(temp),
                lastFullMinutesAgo == null ? null : NOW.minusMinutes(Long.parseLong(lastFullMinutesAgo)),
                NOW.minusSeconds(30), faultCode, disabledFlag);
    }

    private static SlotAllocator.Candidate good(int slotNo, String code, String temp, int soh, int cycles,
                                                String fullAgo) {
        return candidate(slotNo, code, "IDLE_CHARGING", "IN_CABINET_CHARGING", "KNOWN", 90, soh, cycles,
                temp, fullAgo, null, 0);
    }

    @Test
    @DisplayName("硬门槛逐条生效，且被排除的候选都带原因")
    void hardGatesRejectEachReasonIndependently() {
        List<SlotAllocator.Candidate> pool = List.of(
                candidate(1, "B01", "IDLE_CHARGING", "ISOLATED", "KNOWN", 95, 90, 10, "25.0", "5", null, 0),
                candidate(2, "B02", "IDLE_CHARGING", "PENDING_PICKUP", "KNOWN", 95, 90, 10, "25.0", "5", null, 0),
                candidate(3, "B03", "IDLE_CHARGING", "IN_CABINET_CHARGING", "UNKNOWN", 95, 90, 10, "25.0", "5", null, 0),
                candidate(4, "B04", "IDLE_CHARGING", "IN_CABINET_CHARGING", "KNOWN", 95, 90, 10, "25.0", "5", "E9001", 0),
                candidate(5, "B05", "IDLE_CHARGING", "IN_CABINET_CHARGING", "KNOWN", 75, 90, 10, "25.0", "5", null, 0),
                candidate(6, "B06", "IDLE_CHARGING", "IN_CABINET_CHARGING", "KNOWN", 95, 90, 10, "47.0", "5", null, 0),
                candidate(7, "B07", "RESERVED_ORDER", "IN_CABINET_CHARGING", "KNOWN", 95, 90, 10, "25.0", "5", null, 0),
                candidate(8, "B08", "DISABLED", "IN_CABINET_CHARGING", "KNOWN", 95, 90, 10, "25.0", "5", null, 1)
        );

        SlotAllocator.Decision decision = SlotAllocator.pickOffer(pool, LIMITS, null);

        assertThat(decision.available()).as("全是硬门槛命中，不该给出任何仓：" + decision.reasonSummary()).isFalse();
        assertThat(decision.rejections()).extracting(SlotAllocator.Rejection::slotNo)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(decision.rejections()).extracting(SlotAllocator.Rejection::reason)
                .containsExactly("BATTERY_ISOLATED", "BATTERY_PENDING_PICKUP", "LOCATION_UNKNOWN",
                        "BATTERY_FAULT", "SOC_BELOW_MIN", "TEMP_OVER_LIMIT", "SLOT_STATE_RESERVED_ORDER",
                        "SLOT_DISABLED");
    }

    @Test
    @DisplayName("位置未知一律不可分配（O5），不看 SOC 有多高")
    void unknownLocationIsNeverAllocatable() {
        SlotAllocator.Candidate unknown = candidate(1, "B01", "IDLE_CHARGING", "IN_CABINET_CHARGING", "UNKNOWN",
                100, 100, 1, "20.0", "1", null, 0);
        assertThat(SlotAllocator.pickOffer(List.of(unknown), LIMITS, null).available()).isFalse();
    }

    @Test
    @DisplayName("档 1：温度低者优先，即使另一块 SOC 更高")
    void temperatureIsFirstTier() {
        var decision = SlotAllocator.pickOffer(List.of(
                good(1, "B01", "44.0", 99, 5, "1"),
                good(2, "B02", "25.0", 90, 50, "30")), LIMITS, null);
        assertThat(decision.chosenOrThrow().slotNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("档 2：温度并列时 SOH 高者优先，SOH 并列时循环次数少者优先")
    void sohThenCycles() {
        assertThat(SlotAllocator.pickOffer(List.of(
                good(1, "B01", "25.0", 88, 10, "1"),
                good(2, "B02", "25.0", 96, 10, "1")), LIMITS, null).chosenOrThrow().batteryCode())
                .isEqualTo("B02");
        assertThat(SlotAllocator.pickOffer(List.of(
                good(1, "B01", "25.0", 96, 300, "1"),
                good(2, "B02", "25.0", 96, 20, "1")), LIMITS, null).chosenOrThrow().batteryCode())
                .isEqualTo("B02");
    }

    @Test
    @DisplayName("档 3：前两档并列时，距上次充满短者优先")
    void lastFullAtIsThirdTier() {
        assertThat(SlotAllocator.pickOffer(List.of(
                good(1, "B01", "25.0", 96, 20, "120"),
                good(2, "B02", "25.0", 96, 20, "2")), LIMITS, null).chosenOrThrow().batteryCode())
                .isEqualTo("B02");
    }

    @Test
    @DisplayName("全并列时取仓号最小，保证结果确定可重现")
    void deterministicTieBreakBySlotNo() {
        assertThat(SlotAllocator.pickOffer(List.of(
                good(7, "B07", "25.0", 96, 20, "2"),
                good(3, "B03", "25.0", 96, 20, "2")), LIMITS, null).chosenOrThrow().slotNo()).isEqualTo(3);
    }

    @Test
    @DisplayName("SOC 只做门槛不做排序：高 SOC 不该赢过温度更优的仓")
    void socDoesNotParticipateInOrdering() {
        var decision = SlotAllocator.pickOffer(List.of(
                good(1, "B01", "44.0", 99, 1, "1"),      // SOC 90（构造里统一 90）
                good(2, "B02", "22.0", 82, 60, "40")), LIMITS, null);
        assertThat(decision.chosenOrThrow().slotNo())
                .as("温度档优先，SOC 不参与排序").isEqualTo(2);
    }

    @Test
    @DisplayName("同人连续分配惩罚：上一单那块排到最后，但只剩它时仍能成单")
    void sameUserPenaltyIsSoftNotHard() {
        var last = new SlotAllocator.LastAllocation("B01", 1);
        var withAlternative = SlotAllocator.pickOffer(List.of(
                good(1, "B01", "20.0", 99, 1, "1"),       // 最优，但正是上一单那块
                good(2, "B02", "25.0", 90, 20, "5")), LIMITS, last);
        assertThat(withAlternative.chosenOrThrow().batteryCode())
                .as("惩罚生效：不该连续把同一块给同一个人").isEqualTo("B02");

        var onlyLast = SlotAllocator.pickOffer(List.of(good(1, "B01", "20.0", 99, 1, "1")), LIMITS, last);
        assertThat(onlyLast.available())
                .as("惩罚只能降权，不能变成拒单——否则满柜时用户无端被拒").isTrue();
    }

    @Test
    @DisplayName("观测过期的仓不可分配：电池在里面这个事实本身已不可信")
    void staleObservationIsRejected() {
        // 走 helper 而不是直接 new record：Candidate 的字段序与这里惯用顺序不同，
        // 直接写会得到 SLOT_STATE_B01 这种把仓位码当状态的名式错因。
        SlotAllocator.Candidate fresh = good(1, "B01", "25.0", 99, 5, "1");
        SlotAllocator.Candidate stale = new SlotAllocator.Candidate(fresh.slotNo(), fresh.slotState(),
                fresh.batteryCode(), fresh.batteryState(), fresh.locationState(), fresh.soc(), fresh.soh(),
                fresh.cycleCount(), fresh.temp(), fresh.lastFullAt(), NOW.minusMinutes(30), null, 0);

        var decision = SlotAllocator.pickOffer(List.of(stale), LIMITS, null);

        assertThat(decision.rejections()).extracting(SlotAllocator.Rejection::reason)
                .containsExactly("OBSERVATION_STALE");
    }

    @Test
    @DisplayName("归还仓：只从空仓选，优先最久没充电的，并列取仓号最大")
    void returnSlotStrategy() {
        SlotAllocator.Candidate busy = candidate(1, "B01", "IDLE_CHARGING", "IN_CABINET_CHARGING", "KNOWN",
                90, 90, 10, "25.0", "5", null, 0);
        SlotAllocator.Candidate freeRecent = candidate(2, null, "IDLE_EMPTY", null, "KNOWN", null, 90, 10,
                "25.0", "1", null, 0);
        SlotAllocator.Candidate freeIdle = candidate(3, null, "IDLE_EMPTY", null, "KNOWN", null, 90, 10,
                "25.0", "600", null, 0);
        SlotAllocator.Candidate freeIdleSameAgeOther = candidate(4, null, "IDLE_EMPTY", null, "KNOWN", null, 90, 10,
                "25.0", "600", null, 0);

        var decision = SlotAllocator.pickReturn(List.of(busy, freeRecent, freeIdle, freeIdleSameAgeOther), NOW);

        assertThat(decision.chosenOrThrow().slotNo())
                .as("先选最久没参与充电的，并列取仓号最大（与取电方向相反）").isEqualTo(4);
        assertThat(decision.rejections()).extracting(SlotAllocator.Rejection::slotNo)
                .containsExactly(1);
    }

    @Test
    @DisplayName("没有任何空仓时归还仓分配失败并给出原因，而不是返回 null 让上层猜")
    void returnSlotFailsWithReasons() {
        var decision = SlotAllocator.pickReturn(List.of(
                candidate(1, "B01", "IDLE_CHARGING", "IN_CABINET_CHARGING", "KNOWN", 90, 90, 1, "25.0", "5",
                        null, 0)), NOW);
        assertThat(decision.available()).isFalse();
        assertThat(decision.reasonSummary()).contains("SLOT_OCCUPIED");
    }
}
