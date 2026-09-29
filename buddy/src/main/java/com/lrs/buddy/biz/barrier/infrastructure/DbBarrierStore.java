package com.lrs.buddy.biz.barrier.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lrs.buddy.biz.barrier.core.BarrierState;
import com.lrs.buddy.biz.barrier.core.BarrierStatusView;
import com.lrs.buddy.biz.barrier.core.BarrierStore;
import com.lrs.buddy.biz.barrier.core.OptimisticLockConflictException;
import com.lrs.buddy.biz.barrier.core.SchedulePointSpec;
import com.lrs.buddy.biz.barrier.core.StrategySpec;
import com.lrs.buddy.biz.barrier.core.TriggerSource;
import com.lrs.buddy.biz.barrier.mapper.BarrierMapper;
import com.lrs.buddy.biz.barrier.mapper.EventMapper;
import com.lrs.buddy.biz.barrier.mapper.ScheduleMapper;
import com.lrs.buddy.biz.barrier.mapper.StatusMapper;
import com.lrs.buddy.biz.barrier.mapper.StrategyMapper;
import com.lrs.buddy.biz.barrier.entity.Barrier;
import com.lrs.buddy.biz.barrier.entity.BarrierEvent;
import com.lrs.buddy.biz.barrier.entity.BarrierStatus;
import com.lrs.buddy.biz.barrier.entity.SchedulePoint;
import com.lrs.buddy.biz.barrier.entity.Strategy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** {@link BarrierStore} 的 MyBatis-Plus 实现（多杆）：每杆一行状态 upsert；事件只追加；写路径同事务。 */
@Component
public class DbBarrierStore implements BarrierStore {

    private final BarrierMapper barrierMapper;
    private final StrategyMapper strategyMapper;
    private final ScheduleMapper scheduleMapper;
    private final StatusMapper statusMapper;
    private final EventMapper eventMapper;
    private final BarrierMetrics metrics;

    public DbBarrierStore(BarrierMapper barrierMapper, StrategyMapper strategyMapper, ScheduleMapper scheduleMapper,
                          StatusMapper statusMapper, EventMapper eventMapper, BarrierMetrics metrics) {
        this.barrierMapper = barrierMapper;
        this.strategyMapper = strategyMapper;
        this.scheduleMapper = scheduleMapper;
        this.statusMapper = statusMapper;
        this.eventMapper = eventMapper;
        this.metrics = metrics;
    }

    @Override
    public List<Long> enabledBarrierIds() {
        return barrierMapper.selectList(new LambdaQueryWrapper<Barrier>()
                        .eq(Barrier::getEnabled, 1).orderByAsc(Barrier::getId))
                .stream().map(Barrier::getId).toList();
    }

    @Override
    public List<StrategySpec> enabledStrategiesBoundTo(Long barrierId) {
        return strategyMapper.selectBoundEnabled(barrierId).stream()
                .map(s -> new StrategySpec(s.getId(), s.getName(),
                        s.getPriority() == null ? 0 : s.getPriority(),
                        s.getEnabled() == null ? 0 : s.getEnabled()))
                .toList();
    }

    @Override
    public List<SchedulePointSpec> enabledPointsOf(Long strategyId) {
        return scheduleMapper.selectList(new LambdaQueryWrapper<SchedulePoint>()
                        .eq(SchedulePoint::getStrategyId, strategyId)
                        .eq(SchedulePoint::getEnabled, 1)
                        .orderByAsc(SchedulePoint::getTimeOfDay))
                .stream()
                .map(p -> new SchedulePointSpec(
                        p.getTimeOfDay().getHour() * 60 + p.getTimeOfDay().getMinute(),
                        BarrierState.valueOf(p.getPlanState())))
                .toList();
    }

    @Override
    public BarrierStatusView loadStatus(Long barrierId) {
        BarrierStatus s = statusMapper.selectOne(
                new LambdaQueryWrapper<BarrierStatus>().eq(BarrierStatus::getBarrierId, barrierId));
        if (s == null || s.getBarrierState() == null) {
            return new BarrierStatusView(BarrierState.CLOSED, false, null, null);
        }
        return new BarrierStatusView(
                BarrierState.valueOf(s.getBarrierState()),
                s.getManualOverride() != null && s.getManualOverride() == 1,
                s.getLastScheduledAction() == null ? null : BarrierState.valueOf(s.getLastScheduledAction()),
                s.getVersion() == null ? null : s.getVersion().longValue());
    }

    @Override
    public void saveStatus(Long barrierId, BarrierStatusView view) {
        upsertStatus(barrierId, view);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void apply(Long barrierId, BarrierStatusView newStatus, BarrierState state, TriggerSource source,
                      LocalDateTime occurredAt, String message, Long operatorId) {
        upsertStatus(barrierId, newStatus);
        BarrierEvent e = new BarrierEvent();
        e.setBarrierId(barrierId);
        e.setBarrierState(state.name());
        e.setSource(source.name());
        e.setOccurredAt(occurredAt);
        e.setMessage(message);
        e.setOperatorId(operatorId);
        e.setCreateTime(occurredAt);
        eventMapper.insert(e);
        metrics.recordApply(source);
    }

    /**
     * 以乐观锁 CAS 落库某杆状态。view.version()==null → 尚无状态行，插入（并发插入由唯一索引
     * uk_status_barrier 兜底，撞键即视为冲突）；否则按 version 谓词更新，影响行数 0 即版本冲突。
     * 冲突统一抛 {@link OptimisticLockConflictException}，使外层 @Transactional 回滚、不产生孤儿事件。
     */
    private void upsertStatus(Long barrierId, BarrierStatusView view) {
        if (view.version() == null) {
            BarrierStatus row = new BarrierStatus();
            row.setBarrierId(barrierId);
            row.setBarrierState(view.state().name());
            row.setManualOverride(view.manualOverride() ? 1 : 0);
            row.setLastScheduledAction(view.lastScheduled() == null ? null : view.lastScheduled().name());
            try {
                statusMapper.insert(row);
            } catch (DuplicateKeyException e) {
                metrics.recordConflict("duplicate-insert");
                throw new OptimisticLockConflictException("杆[" + barrierId + "]状态行被并发创建", e);
            }
            metrics.updateState(barrierId, view.state());
            return;
        }
        int rows = statusMapper.casUpdate(barrierId, view.state().name(), view.manualOverride() ? 1 : 0,
                view.lastScheduled() == null ? null : view.lastScheduled().name(),
                LocalDateTime.now(), view.version());
        if (rows == 0) {
            metrics.recordConflict("version");
            throw new OptimisticLockConflictException(
                    "杆[" + barrierId + "]状态已被其他实例修改（期望 version=" + view.version() + "）");
        }
        metrics.updateState(barrierId, view.state());
    }
}
