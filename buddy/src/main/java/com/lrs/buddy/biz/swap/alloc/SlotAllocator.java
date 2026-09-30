package com.lrs.buddy.biz.swap.alloc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 满电仓 / 归还仓分配（纯函数，swap-order-fsm §12.1）。
 *
 * 为什么写成纯函数而不是 service：
 * 分配是"结果必须可重现、可单档断言"的决策。混在 service 里就只能靠起 Spring 上下文打数据库测，
 * 而数据库里的数据一变，断言的意义就从"规则对不对"退化成"这批数据凑出什么结果"。
 * M2 的框架回流项之一就是这个决策层。
 *
 * 三条硬口径：
 * 1 **硬门槛一个都不减**（§12.1）：隔离/待取回/维护/丢失/报废/有故障码/位置未知/超温/SOC 不足/观测过期，
 *    任一命中即不可分配。其中 `LOCATION_UNKNOWN` 一律不可分配（O5）：不知道在哪就不能给用户。
 * 2 **SOC 只做门槛不做排序**。用 SOC 排序的结果永远是"把刚充满的那块给下一个人"，周转不均。
 * 3 **排序是显式优先级链，不是加权打分**：权重需要拍脑袋参数且不可证；
 *    优先级链没有待定参数、逐档可单独写断言、结果确定可重现。
 *
 * 每个被排除的候选都带**原因**返回，而不是只给一个"没有可用仓"：
 * 现场最常见的追问是"为什么没给我"，只有聚合结论的回答最后都会变成"再试一次"。
 */
public final class SlotAllocator {

    /** 参与分配的仓位快照（字段口径来自 swap_slot + swap_battery 投影）。 */
    public record Candidate(int slotNo,
                            String slotState,
                            String batteryCode,
                            String batteryState,
                            String locationState,
                            Integer soc,
                            BigDecimal soh,
                            Integer cycleCount,
                            BigDecimal temp,
                            LocalDateTime lastFullAt,
                            LocalDateTime lastDetectedAt,
                            String faultCode,
                            Integer disabledFlag) {
    }

    /** 阈值来自产品默认值 + 站点收紧（站点只能收紧不放宽，DB CHECK 已钉住）。 */
    public record Thresholds(int minSoc, BigDecimal maxAllocTemp, Duration freshness, LocalDateTime now) {
    }

    /** 同人连续分配惩罚的输入：该用户上一单取走的电池码与仓位号（可为 null）。 */
    public record LastAllocation(String batteryCode, Integer slotNo) {
    }

    public record Rejection(int slotNo, String batteryCode, String reason) {
    }

    /** @param chosen 命中的候选；@param rejections 被排除的候选与原因（按仓号升序，便于对照） */
    public record Decision(Optional<Candidate> chosen, List<Rejection> rejections) {

        public boolean available() {
            return chosen.isPresent();
        }

        /** 断言型取值：没选中就直接抛并把排除原因带进消息，不让测试只看到一个空 Optional。 */
        public Candidate chosenOrThrow() {
            return chosen.orElseThrow(() -> new IllegalStateException("无可用仓：" + reasonSummary()));
        }

        public String reasonSummary() {
            return rejections.stream().map(r -> r.slotNo() + ":" + r.reason()).toList().toString();
        }
    }

    private static final List<String> UNAVAILABLE_STATES =
            List.of("ISOLATED", "PENDING_PICKUP", "MAINTENANCE", "LOST", "SCRAPPED");

    private SlotAllocator() {
    }

    /** 取电仓（满电电池）选择。 */
    public static Decision pickOffer(List<Candidate> candidates, Thresholds limits, LastAllocation last) {
        List<Rejection> rejections = new ArrayList<>();
        List<Candidate> passable = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String reason = hardGate(candidate, limits);
            if (reason == null) {
                passable.add(candidate);
            } else {
                rejections.add(new Rejection(candidate.slotNo(), candidate.batteryCode(), reason));
            }
        }
        if (passable.isEmpty()) {
            return new Decision(Optional.empty(), rejections.stream().sorted(
                    Comparator.comparingInt(Rejection::slotNo)).toList());
        }
        // 同人连续惩罚：与上一单同一块电池的候选排到最后（不剔除——只剩它时仍须能成单）
        Comparator<Candidate> penalty = Comparator.comparing(
                candidate -> last != null && last.batteryCode() != null
                        && last.batteryCode().equals(candidate.batteryCode()) ? 1 : 0);
        Candidate chosen = passable.stream()
                .sorted(penalty
                        // 档 1：温度低优先
                        .thenComparing(Candidate::temp)
                        // 档 2：SOH 高优先，并列时循环次数少优先
                        .thenComparing(Candidate::soh, Comparator.reverseOrder())
                        .thenComparing(Candidate::cycleCount)
                        // 档 3：距上次充满短优先
                        .thenComparing(Candidate::lastFullAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        // 仍并列：仓号最小，保证结果确定
                        .thenComparingInt(Candidate::slotNo))
                .findFirst().orElseThrow();
        return new Decision(Optional.of(chosen), rejections.stream().sorted(
                Comparator.comparingInt(Rejection::slotNo)).toList());
    }

    /**
     * 归还仓（空仓）选择。
     *
     * §12.1 的口径是"优先选最不忙的仓，避免把刚腾出来的仓立刻占住充电资源"。
     * 落到可测字段上 = 空闲仓里 lastFullAt 最早（最久没参与充电）者优先，并列取仓号最大
     * ——方向与取电仓相反，两条策略不会互相把对方最优解抢光。
     */
    public static Decision pickReturn(List<Candidate> candidates, LocalDateTime now) {
        List<Rejection> rejections = new ArrayList<>();
        List<Candidate> free = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.slotState() == null || !"IDLE_EMPTY".equals(candidate.slotState())
                    || candidate.batteryCode() != null) {
                rejections.add(new Rejection(candidate.slotNo(), null,
                        candidate.batteryCode() != null ? "SLOT_OCCUPIED" : "SLOT_NOT_EMPTY"));
                continue;
            }
            if (candidate.disabledFlag() != null && candidate.disabledFlag() == 1) {
                rejections.add(new Rejection(candidate.slotNo(), null, "SLOT_DISABLED"));
                continue;
            }
            free.add(candidate);
        }
        if (free.isEmpty()) {
            return new Decision(Optional.empty(), rejections.stream().sorted(
                    Comparator.comparingInt(Rejection::slotNo)).toList());
        }
        Candidate chosen = free.stream()
                .sorted(Comparator.comparing(Candidate::lastFullAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Comparator.comparingInt(Candidate::slotNo).reversed()))
                .findFirst().orElseThrow();
        return new Decision(Optional.of(chosen), rejections.stream().sorted(
                Comparator.comparingInt(Rejection::slotNo)).toList());
    }

    /** @return 不通过时返回原因码；通过返回 null */
    private static String hardGate(Candidate candidate, Thresholds limits) {
        if (candidate.batteryState() != null && UNAVAILABLE_STATES.contains(candidate.batteryState())) {
            return "BATTERY_" + candidate.batteryState();
        }
        if (!"KNOWN".equals(candidate.locationState())) {
            // O5：位置未知一律不可分配，"大概在柜里"不构成能给用户的依据
            return "LOCATION_UNKNOWN";
        }
        if (candidate.faultCode() != null && !candidate.faultCode().isBlank()) {
            return "BATTERY_FAULT";
        }
        if (candidate.disabledFlag() != null && candidate.disabledFlag() == 1) {
            return "SLOT_DISABLED";
        }
        if (!"IDLE_CHARGING".equals(candidate.slotState())) {
            // 取电候选只能是 IDLE_CHARGING（有电池且未被预占/门未开）。
            // 注意 FULL 是 charge_state 不是 slot_state：把它当仓位状态写会永远匹不上，
            // 症状是“柜里有满电仓却报 SOC_BELOW_MIN/SLOT_STATE”这类错因混淆。
            return "SLOT_STATE_" + candidate.slotState();
        }
        if (candidate.soc() == null || candidate.soc() < limits.minSoc()) {
            return "SOC_BELOW_MIN";
        }
        if (candidate.temp() == null || candidate.temp().compareTo(limits.maxAllocTemp()) > 0) {
            return "TEMP_OVER_LIMIT";
        }
        if (candidate.lastDetectedAt() == null
                || Duration.between(candidate.lastDetectedAt(), limits.now()).compareTo(limits.freshness()) > 0) {
            // 观测过期时"电池在里面"这个事实本身已不可信，宁可不给也不能给一块可能早被取走的电池
            return "OBSERVATION_STALE";
        }
        return null;
    }
}
