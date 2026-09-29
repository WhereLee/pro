package com.lrs.buddy.biz.barrier.controller;

import com.lrs.buddy.framework.modules.log.annotation.OperateLog;
import com.lrs.buddy.framework.modules.log.enums.BusinessType;
import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.biz.barrier.service.ScheduleAdminService;
import com.lrs.buddy.biz.barrier.model.form.ScheduleCreateForm;
import com.lrs.buddy.biz.barrier.model.vo.ScheduleVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 计划点配置接口（全生命周期）。真实路径含 context-path：/api/barrier/schedules。 */
@RestController
@RequestMapping("/barrier/schedules")
@PreAuthorize("hasAuthority('schedule:manage')")
public class BarrierScheduleController {

    private final ScheduleAdminService service;

    public BarrierScheduleController(ScheduleAdminService service) {
        this.service = service;
    }

    @GetMapping
    public R<List<ScheduleVO>> list() {
        return R.ok(service.list());
    }

    @PostMapping
    @OperateLog(title = "计划点", businessType = BusinessType.INSERT)
    public R<Long> create(@Valid @RequestBody ScheduleCreateForm form) {
        return R.ok(service.create(form), "新增成功");
    }

    @PutMapping("/{id}")
    @OperateLog(title = "计划点", businessType = BusinessType.UPDATE)
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ScheduleCreateForm form) {
        service.update(id, form);
        return R.ok(null, "修改成功");
    }

    @PutMapping("/{id}/enabled")
    @OperateLog(title = "计划点", businessType = BusinessType.UPDATE)
    public R<Void> setEnabled(@PathVariable Long id, @RequestParam int enabled) {
        service.setEnabled(id, enabled);
        return R.ok(null, enabled == 1 ? "已启用" : "已停用");
    }

    @DeleteMapping("/{id}")
    @OperateLog(title = "计划点", businessType = BusinessType.DELETE)
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok(null, "删除成功");
    }
}
