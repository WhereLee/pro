package com.lrs.buddy.biz.barrier.core;

import java.util.List;

/**
 * 计划点 → "此刻应处于什么状态"的纯计算。系统全部调度正确性的根，故做成无副作用静态函数、重点测试。
 *
 * <p>规则（时间窗以"到点设置目标态"表达，每天重复）：
 * <ul>
 *   <li>取启用点中 {@code minuteOfDay <= now} 的<b>最大</b>点，其状态即当前应处状态；</li>
 *   <li>若当天还没有任何点被越过（now 早于首个点），<b>回绕</b>取全天最大点（= 昨天最后一次动作），
 *       由此天然处理跨午夜窗口；</li>
 *   <li>没有任何启用点 → 返回 {@code null}（系统保持现状、不擅自动作）。</li>
 * </ul>
 */
public final class ScheduleComputer {

    private ScheduleComputer() {
    }

    public static BarrierState scheduledStateAt(int minuteOfDay, List<SchedulePointSpec> enabledPoints) {
        if (enabledPoints == null || enabledPoints.isEmpty()) {
            return null;
        }
        BarrierState current = null;
        BarrierState lastOfDay = null;
        int bestPassed = -1;
        int bestOverall = -1;
        for (SchedulePointSpec p : enabledPoints) {
            if (p.minuteOfDay() > bestOverall) {
                bestOverall = p.minuteOfDay();
                lastOfDay = p.state();
            }
            if (p.minuteOfDay() <= minuteOfDay && p.minuteOfDay() > bestPassed) {
                bestPassed = p.minuteOfDay();
                current = p.state();
            }
        }
        // now 早于当天首个点 → 回绕到昨天最后一次动作
        return current != null ? current : lastOfDay;
    }
}
