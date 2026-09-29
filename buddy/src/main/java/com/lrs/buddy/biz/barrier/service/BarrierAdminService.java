package com.lrs.buddy.biz.barrier.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lrs.buddy.framework.common.exception.BusinessException;
import com.lrs.buddy.biz.barrier.core.BarrierStatusView;
import com.lrs.buddy.biz.barrier.core.BarrierStore;
import com.lrs.buddy.biz.barrier.mapper.BarrierMapper;
import com.lrs.buddy.biz.barrier.mapper.StrategyMapper;
import com.lrs.buddy.biz.barrier.entity.Barrier;
import com.lrs.buddy.biz.barrier.model.form.BarrierForm;
import com.lrs.buddy.biz.barrier.model.vo.BarrierVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 杆的全生命周期：查（含当前态）/ 建 / 改 / 启停 / 删（逻辑删除，删前校验无策略绑定）。 */
@Service
public class BarrierAdminService {

    private final BarrierMapper mapper;
    private final StrategyMapper strategyMapper;
    private final BarrierStore store;

    public BarrierAdminService(BarrierMapper mapper, StrategyMapper strategyMapper, BarrierStore store) {
        this.mapper = mapper;
        this.strategyMapper = strategyMapper;
        this.store = store;
    }

    public List<BarrierVO> list() {
        return mapper.selectList(new LambdaQueryWrapper<Barrier>().orderByAsc(Barrier::getId))
                .stream()
                .map(b -> {
                    BarrierStatusView s = store.loadStatus(b.getId());
                    return new BarrierVO(b.getId(), b.getName(), b.getLocation(), b.getEnabled(),
                            s.state().name(), s.manualOverride());
                })
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(BarrierForm form) {
        Barrier b = new Barrier();
        b.setName(form.getName());
        b.setLocation(form.getLocation());
        b.setEnabled(form.getEnabled() == null ? 1 : form.getEnabled());
        mapper.insert(b);
        return b.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BarrierForm form) {
        Barrier exist = require(id);
        exist.setName(form.getName());
        exist.setLocation(form.getLocation());
        if (form.getEnabled() != null) {
            exist.setEnabled(form.getEnabled());
        }
        if (mapper.updateById(exist) == 0) {
            throw new BusinessException("杆已被他人修改，请重试");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(Long id, int enabled) {
        if (enabled != 0 && enabled != 1) {
            throw new BusinessException("enabled 只能是 0 或 1");
        }
        Barrier exist = require(id);
        exist.setEnabled(enabled);
        mapper.updateById(exist);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        require(id);
        if (strategyMapper.countBindingsOfBarrier(id) > 0) {
            throw new BusinessException("该杆仍被策略绑定，请先解绑后再删除");
        }
        mapper.deleteById(id);
    }

    private Barrier require(Long id) {
        Barrier b = mapper.selectById(id);
        if (b == null) {
            throw new BusinessException("杆不存在：" + id);
        }
        return b;
    }
}
