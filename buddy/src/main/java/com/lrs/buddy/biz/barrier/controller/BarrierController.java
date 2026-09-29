package com.lrs.buddy.biz.barrier.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lrs.buddy.framework.modules.log.annotation.OperateLog;
import com.lrs.buddy.framework.modules.log.enums.BusinessType;
import com.lrs.buddy.framework.common.exception.BusinessException;
import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.framework.common.response.ResultCode;
import com.lrs.buddy.biz.barrier.core.BarrierEngine;
import com.lrs.buddy.biz.barrier.core.BarrierState;
import com.lrs.buddy.biz.barrier.core.BarrierStatusView;
import com.lrs.buddy.biz.barrier.core.BarrierStore;
import com.lrs.buddy.biz.barrier.core.OptimisticLockConflictException;
import com.lrs.buddy.biz.barrier.mapper.EventMapper;
import com.lrs.buddy.biz.barrier.entity.BarrierEvent;
import com.lrs.buddy.framework.security.LoginUser;
import com.lrs.buddy.biz.barrier.service.BarrierAdminService;
import com.lrs.buddy.biz.barrier.service.ActionParser;
import com.lrs.buddy.biz.barrier.model.form.BarrierForm;
import com.lrs.buddy.biz.barrier.model.form.ManualCommandForm;
import com.lrs.buddy.biz.barrier.model.vo.BarrierVO;
import com.lrs.buddy.biz.barrier.model.vo.EventVO;
import com.lrs.buddy.biz.barrier.model.vo.StatusVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/** 杆运行面 + 管理面（RESTful）。真实路径含 context-path：/api/barriers/**。 */
@RestController
@RequestMapping("/barriers")
public class BarrierController {

    private final BarrierAdminService adminService;
    private final BarrierEngine engine;
    private final BarrierStore store;
    private final EventMapper eventMapper;
    private final ZoneId zone;

    public BarrierController(BarrierAdminService adminService, BarrierEngine engine, BarrierStore store,
                             EventMapper eventMapper, @Value("${barrier.zone:Asia/Shanghai}") String zone) {
        this.adminService = adminService;
        this.engine = engine;
        this.store = store;
        this.eventMapper = eventMapper;
        this.zone = ZoneId.of(zone);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('barrier:status:read')")
    public R<List<BarrierVO>> list() {
        return R.ok(adminService.list());
    }

    @GetMapping("/{id}/status")
    @PreAuthorize("hasAuthority('barrier:status:read')")
    public R<StatusVO> status(@PathVariable Long id) {
        BarrierStatusView v = store.loadStatus(id);
        return R.ok(new StatusVO(v.state().name(), v.manualOverride(),
                v.lastScheduled() == null ? null : v.lastScheduled().name()));
    }

    @GetMapping("/{id}/events")
    @PreAuthorize("hasAuthority('barrier:events:read')")
    public R<List<EventVO>> events(@PathVariable Long id, @RequestParam(defaultValue = "50") int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        List<BarrierEvent> rows = eventMapper.selectList(new LambdaQueryWrapper<BarrierEvent>()
                .eq(BarrierEvent::getBarrierId, id)
                .orderByDesc(BarrierEvent::getOccurredAt).orderByDesc(BarrierEvent::getId)
                .last("limit " + n));
        return R.ok(rows.stream()
                .map(e -> new EventVO(e.getId(), e.getBarrierState(), e.getSource(), e.getOccurredAt(), e.getOperatorId()))
                .toList());
    }

    @PostMapping("/{id}/manual")
    @PreAuthorize("hasAuthority('barrier:manual')")
    @OperateLog(title = "杆控制", businessType = BusinessType.UPDATE)
    public R<StatusVO> manual(@PathVariable Long id, @Valid @RequestBody ManualCommandForm form,
                              @AuthenticationPrincipal LoginUser user) {
        BarrierState target = ActionParser.parse(form.getAction());
        Long operatorId = user != null ? user.getUserId() : null;
        try {
            engine.manual(id, target, ZonedDateTime.now(zone), operatorId);
        } catch (OptimisticLockConflictException e) {
            // 跨实例并发写、引擎有界重试仍耗尽：翻译为业务冲突码（HTTP 200 + code 409），不外泄内部异常
            throw new BusinessException(ResultCode.CONFLICT, "杆状态正被其他操作修改，请稍后重试");
        }
        return status(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('barrier:manage')")
    @OperateLog(title = "杆管理", businessType = BusinessType.INSERT)
    public R<Long> create(@Valid @RequestBody BarrierForm form) {
        return R.ok(adminService.create(form), "新增成功");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('barrier:manage')")
    @OperateLog(title = "杆管理", businessType = BusinessType.UPDATE)
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody BarrierForm form) {
        adminService.update(id, form);
        return R.ok(null, "修改成功");
    }

    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasAuthority('barrier:manage')")
    @OperateLog(title = "杆管理", businessType = BusinessType.UPDATE)
    public R<Void> setEnabled(@PathVariable Long id, @RequestParam int enabled) {
        adminService.setEnabled(id, enabled);
        return R.ok(null, enabled == 1 ? "已启用" : "已停用");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('barrier:manage')")
    @OperateLog(title = "杆管理", businessType = BusinessType.DELETE)
    public R<Void> delete(@PathVariable Long id) {
        adminService.delete(id);
        return R.ok(null, "删除成功");
    }
}
