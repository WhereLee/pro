package com.lrs.buddy.biz.barrier.core;

/**
 * 当前态视图（引擎读写的状态快照，与实体解耦）。
 *
 * @param state          杆此刻记录的状态
 * @param manualOverride 是否有未过期的人工覆盖（true 时定时引擎保持沉默，直到下一次边界跨越）
 * @param lastScheduled  最近一次"已应用的定时目标状态"，用于识别是否跨越了计划边界；可为 null（尚未对齐）
 * @param version        乐观锁版本：读取时的版本，写回时作 CAS 基线，跨实例并发写据此检测冲突；null 表示尚无状态行
 */
public record BarrierStatusView(BarrierState state, boolean manualOverride, BarrierState lastScheduled, Long version) {
}
