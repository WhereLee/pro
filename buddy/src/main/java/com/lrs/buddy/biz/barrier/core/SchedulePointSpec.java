package com.lrs.buddy.biz.barrier.core;

import java.time.LocalTime;

/**
 * 计划点规格（纯核心模型，与持久化实体解耦，便于单测）。
 *
 * <p>用"一天中的第几分钟"(0..1440)表达时刻，避免时区/夏令时在比较层的干扰；
 * 决策是分钟粒度。
 */
public record SchedulePointSpec(int minuteOfDay, BarrierState state) {

    public static SchedulePointSpec of(LocalTime time, BarrierState state) {
        return new SchedulePointSpec(time.getHour() * 60 + time.getMinute(), state);
    }
}
