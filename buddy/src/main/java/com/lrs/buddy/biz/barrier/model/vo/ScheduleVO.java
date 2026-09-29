package com.lrs.buddy.biz.barrier.model.vo;

import java.time.LocalTime;

public record ScheduleVO(Long id, Long strategyId, LocalTime timeOfDay, String planState, Integer enabled, String name) {
}
