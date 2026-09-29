package com.lrs.buddy.biz.barrier.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lrs.buddy.framework.common.exception.BusinessException;
import com.lrs.buddy.biz.barrier.core.BarrierState;
import com.lrs.buddy.biz.barrier.mapper.ScheduleMapper;
import com.lrs.buddy.biz.barrier.mapper.StrategyMapper;
import com.lrs.buddy.biz.barrier.entity.SchedulePoint;
import com.lrs.buddy.biz.barrier.model.form.ScheduleCreateForm;
import com.lrs.buddy.biz.barrier.model.vo.ScheduleVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

/** 计划点全生命周期：建 / 改 / 启停 / 删（逻辑删除）/ 查。计划点归属某策略。 */
@Service
public class ScheduleAdminService {

    private static final long DEFAULT_STRATEGY_ID = 1L;

    private final ScheduleMapper mapper;
    private final StrategyMapper strategyMapper;

    public ScheduleAdminService(ScheduleMapper mapper, StrategyMapper strategyMapper) {
        this.mapper = mapper;
        this.strategyMapper = strategyMapper;
    }

    public List<ScheduleVO> list() {
        return mapper.selectList(new LambdaQueryWrapper<SchedulePoint>()
                        .orderByAsc(SchedulePoint::getTimeOfDay))
                .stream()
                .map(p -> new ScheduleVO(p.getId(), p.getStrategyId(), p.getTimeOfDay(),
                        p.getPlanState(), p.getEnabled(), p.getName()))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(ScheduleCreateForm form) {
        long strategyId = resolveStrategy(form.getStrategyId());
        String state = normalizeState(form.getPlanState());
        assertTimeNotDuplicated(strategyId, form.getTimeOfDay(), null);
        SchedulePoint p = new SchedulePoint();
        p.setStrategyId(strategyId);
        p.setTimeOfDay(form.getTimeOfDay());
        p.setPlanState(state);
        p.setEnabled(form.getEnabled() == null ? 1 : form.getEnabled());
        p.setName(form.getName());
        mapper.insert(p);
        return p.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ScheduleCreateForm form) {
        SchedulePoint exist = require(id);
        long strategyId = form.getStrategyId() != null ? resolveStrategy(form.getStrategyId()) : exist.getStrategyId();
        String state = normalizeState(form.getPlanState());
        assertTimeNotDuplicated(strategyId, form.getTimeOfDay(), id);
        exist.setStrategyId(strategyId);
        exist.setTimeOfDay(form.getTimeOfDay());
        exist.setPlanState(state);
        exist.setEnabled(form.getEnabled() == null ? exist.getEnabled() : form.getEnabled());
        exist.setName(form.getName());
        if (mapper.updateById(exist) == 0) {
            throw new BusinessException("计划点已被他人修改，请重试");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(Long id, int enabled) {
        if (enabled != 0 && enabled != 1) {
            throw new BusinessException("enabled 只能是 0 或 1");
        }
        SchedulePoint exist = require(id);
        exist.setEnabled(enabled);
        mapper.updateById(exist);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        require(id);
        mapper.deleteById(id);
    }

    private long resolveStrategy(Long strategyId) {
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        if (strategyMapper.selectById(sid) == null) {
            throw new BusinessException("策略不存在：" + sid);
        }
        return sid;
    }

    private SchedulePoint require(Long id) {
        SchedulePoint p = mapper.selectById(id);
        if (p == null) {
            throw new BusinessException("计划点不存在：" + id);
        }
        return p;
    }

    private String normalizeState(String raw) {
        return ActionParser.parse(raw).name();
    }

    private void assertTimeNotDuplicated(long strategyId, LocalTime time, Long excludeId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<SchedulePoint>()
                .eq(SchedulePoint::getStrategyId, strategyId)
                .eq(SchedulePoint::getTimeOfDay, time)
                .eq(SchedulePoint::getEnabled, 1)
                .ne(excludeId != null, SchedulePoint::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException("该策略下此时刻已存在启用的计划点：" + time);
        }
    }
}
