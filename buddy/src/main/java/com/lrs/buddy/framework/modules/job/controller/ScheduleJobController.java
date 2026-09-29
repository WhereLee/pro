package com.lrs.buddy.framework.modules.job.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lrs.buddy.framework.common.model.PageResult;
import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.framework.modules.job.entity.SysJob;
import com.lrs.buddy.framework.modules.job.service.ScheduleJobService;
import com.lrs.buddy.framework.modules.log.annotation.OperateLog;
import com.lrs.buddy.framework.modules.log.enums.BusinessType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 定时任务管理。
 */
@Tag(name = "定时任务")
@RestController
@RequestMapping("/sys/job")
@RequiredArgsConstructor
public class ScheduleJobController {

    private final ScheduleJobService jobService;

    @Operation(summary = "任务分页列表")
    @PreAuthorize("hasAuthority('sys:job:list')")
    @PostMapping("/page")
    public R<PageResult<SysJob>> page(@RequestBody com.lrs.buddy.framework.common.model.PageQuery query) {
        LambdaQueryWrapper<SysJob> wrapper = new LambdaQueryWrapper<SysJob>()
                .orderByAsc(SysJob::getId);
        IPage<SysJob> page = jobService.page(query.toPage(), wrapper);
        return R.ok(PageResult.of(page));
    }

    /**
     * 列出所有可作为执行体的 Bean。
     *
     * <p>前端新增任务时从这个接口拿候选列表，
     * 避免管理员手工输入 bean 名称导致拼错、且错误要到运行时才暴露。
     */
    @Operation(summary = "可用执行体列表")
    @PreAuthorize("hasAuthority('sys:job:list')")
    @GetMapping("/beans")
    public R<List<String>> beans() {
        return R.ok(jobService.taskBeanNames());
    }

    @Operation(summary = "新增任务")
    @OperateLog(title = "定时任务", businessType = BusinessType.INSERT)
    @PreAuthorize("hasAuthority('sys:job:save')")
    @PostMapping
    public R<Void> save(@RequestBody SysJob job) {
        if (!StringUtils.hasText(job.getJobName()) || !StringUtils.hasText(job.getBeanName())) {
            throw new com.lrs.buddy.framework.common.exception.BusinessException("任务名称与执行体不能为空");
        }
        jobService.createJob(job);
        return R.ok(null, "新增成功");
    }

    @Operation(summary = "修改任务")
    @OperateLog(title = "定时任务", businessType = BusinessType.UPDATE)
    @PreAuthorize("hasAuthority('sys:job:update')")
    @PutMapping
    public R<Void> update(@RequestBody SysJob job) {
        jobService.updateJob(job);
        return R.ok(null, "修改成功");
    }

    @Operation(summary = "删除任务（支持批量）")
    @OperateLog(title = "定时任务", businessType = BusinessType.DELETE)
    @PreAuthorize("hasAuthority('sys:job:remove')")
    @DeleteMapping
    public R<Void> remove(@RequestBody List<String> ids) {
        jobService.deleteJobs(ids);
        return R.ok(null, "删除成功");
    }

    @Operation(summary = "暂停任务")
    @OperateLog(title = "定时任务", businessType = BusinessType.UPDATE)
    @PreAuthorize("hasAuthority('sys:job:update')")
    @PutMapping("/pause/{id}")
    public R<Void> pause(@PathVariable Long id) {
        jobService.pause(id);
        return R.ok(null, "已暂停");
    }

    @Operation(summary = "恢复任务")
    @OperateLog(title = "定时任务", businessType = BusinessType.UPDATE)
    @PreAuthorize("hasAuthority('sys:job:update')")
    @PutMapping("/resume/{id}")
    public R<Void> resume(@PathVariable Long id) {
        jobService.resume(id);
        return R.ok(null, "已恢复");
    }

    @Operation(summary = "立即执行一次")
    @OperateLog(title = "定时任务", businessType = BusinessType.OTHER)
    @PreAuthorize("hasAuthority('sys:job:update')")
    @PutMapping("/run/{id}")
    public R<Void> run(@PathVariable Long id) {
        jobService.runOnce(id);
        return R.ok(null, "已触发");
    }
}
