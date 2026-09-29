package com.lrs.buddy.biz.barrier.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lrs.buddy.framework.common.exception.BusinessException;
import com.lrs.buddy.biz.barrier.mapper.BarrierMapper;
import com.lrs.buddy.biz.barrier.mapper.ScheduleMapper;
import com.lrs.buddy.biz.barrier.mapper.StrategyMapper;
import com.lrs.buddy.biz.barrier.entity.SchedulePoint;
import com.lrs.buddy.biz.barrier.entity.Strategy;
import com.lrs.buddy.biz.barrier.model.form.StrategyForm;
import com.lrs.buddy.biz.barrier.model.vo.StrategyVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 策略全生命周期：建 / 改 / 启停 / 删（逻辑删除）/ 查。删策略前校验其下无计划点（引用完整）。 */
@Service
public class StrategyAdminService {

    private final StrategyMapper mapper;
    private final ScheduleMapper scheduleMapper;
    private final BarrierMapper barrierMapper;

    public StrategyAdminService(StrategyMapper mapper, ScheduleMapper scheduleMapper, BarrierMapper barrierMapper) {
        this.mapper = mapper;
        this.scheduleMapper = scheduleMapper;
        this.barrierMapper = barrierMapper;
    }

    public List<StrategyVO> list() {
        return mapper.selectList(new LambdaQueryWrapper<Strategy>()
                        .orderByDesc(Strategy::getPriority).orderByAsc(Strategy::getId))
                .stream()
                .map(s -> new StrategyVO(s.getId(), s.getName(), s.getDescription(), s.getEnabled(), s.getPriority()))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(StrategyForm form) {
        Strategy s = new Strategy();
        s.setName(form.getName());
        s.setDescription(form.getDescription());
        s.setEnabled(form.getEnabled() == null ? 1 : form.getEnabled());
        s.setPriority(form.getPriority() == null ? 0 : form.getPriority());
        mapper.insert(s);
        return s.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, StrategyForm form) {
        Strategy exist = require(id);
        exist.setName(form.getName());
        exist.setDescription(form.getDescription());
        if (form.getEnabled() != null) {
            exist.setEnabled(form.getEnabled());
        }
        if (form.getPriority() != null) {
            exist.setPriority(form.getPriority());
        }
        if (mapper.updateById(exist) == 0) {
            throw new BusinessException("策略已被他人修改，请重试");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(Long id, int enabled) {
        if (enabled != 0 && enabled != 1) {
            throw new BusinessException("enabled 只能是 0 或 1");
        }
        Strategy exist = require(id);
        exist.setEnabled(enabled);
        mapper.updateById(exist);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        require(id);
        Long points = scheduleMapper.selectCount(
                new LambdaQueryWrapper<SchedulePoint>().eq(SchedulePoint::getStrategyId, id));
        if (points != null && points > 0) {
            throw new BusinessException("该策略下仍有 " + points + " 个计划点，请先删除或转移后再删策略");
        }
        mapper.deleteById(id);
    }

    /** 整体替换策略绑定的杆（N:M）。 */
    @Transactional(rollbackFor = Exception.class)
    public void setBarriers(Long strategyId, List<Long> barrierIds) {
        require(strategyId);
        if (barrierIds != null) {
            for (Long bid : barrierIds) {
                if (barrierMapper.selectById(bid) == null) {
                    throw new BusinessException("杆不存在：" + bid);
                }
            }
        }
        mapper.deleteBindings(strategyId);
        if (barrierIds != null) {
            for (Long bid : barrierIds) {
                mapper.bind(strategyId, bid);
            }
        }
    }

    public List<Long> barrierIds(Long strategyId) {
        require(strategyId);
        return mapper.selectBarrierIds(strategyId);
    }

    private Strategy require(Long id) {
        Strategy s = mapper.selectById(id);
        if (s == null) {
            throw new BusinessException("策略不存在：" + id);
        }
        return s;
    }
}
