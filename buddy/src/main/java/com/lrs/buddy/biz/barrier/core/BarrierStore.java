package com.lrs.buddy.biz.barrier.core;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 持久化端口（多杆）：核心只依赖此接口，可脱库单测。
 * 调度以"杆"为单位：每根杆取其绑定的最高优先级启用策略算目标态。
 */
public interface BarrierStore {

    /** 所有启用杆的 id。 */
    List<Long> enabledBarrierIds();

    /** 绑定到某杆、且启用的策略（供 StrategyResolver 选生效策略）。 */
    List<StrategySpec> enabledStrategiesBoundTo(Long barrierId);

    /** 某策略下启用的计划时间点。 */
    List<SchedulePointSpec> enabledPointsOf(Long strategyId);

    /** 读取某杆当前态（不存在则默认 CLOSED、无覆盖、无上次定时动作）。 */
    BarrierStatusView loadStatus(Long barrierId);

    /** 仅保存某杆状态、不记事件。 */
    void saveStatus(Long barrierId, BarrierStatusView view);

    /** 应用某杆一次状态变更：持久化新状态 + 追加事件（同一事务）。operatorId 为手动操作人，定时为 null。 */
    void apply(Long barrierId, BarrierStatusView newStatus, BarrierState state, TriggerSource source,
               LocalDateTime occurredAt, String message, Long operatorId);
}
