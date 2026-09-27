package com.lrs.buddy.modules.sys.controller;

import com.lrs.buddy.common.BusinessException;
import com.lrs.buddy.common.R;
import com.lrs.buddy.modules.log.annotation.OperateLog;
import com.lrs.buddy.modules.log.enums.BusinessType;
import com.lrs.buddy.modules.sys.entity.SysDept;
import com.lrs.buddy.modules.sys.model.vo.SysDeptVO;
import com.lrs.buddy.modules.sys.service.SysDeptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 部门管理。
 */
@Tag(name = "部门管理")
@RestController
@RequestMapping("/sys/dept")
@RequiredArgsConstructor
public class SysDeptController {

    private final SysDeptService deptService;

    @Operation(summary = "部门树")
    @PreAuthorize("hasAuthority('sys:dept:list')")
    @GetMapping("/tree")
    public R<List<SysDeptVO>> tree() {
        return R.ok(deptService.tree());
    }

    @Operation(summary = "新增部门")
    @OperateLog(title = "部门管理", businessType = BusinessType.INSERT)
    @PreAuthorize("hasAuthority('sys:dept:save')")
    @PostMapping
    public R<Void> save(@RequestBody SysDept dept) {
        deptService.saveDept(dept);
        return R.ok(null, "新增成功");
    }

    @Operation(summary = "修改部门")
    @OperateLog(title = "部门管理", businessType = BusinessType.UPDATE)
    @PreAuthorize("hasAuthority('sys:dept:update')")
    @PutMapping
    public R<Void> update(@RequestBody SysDept dept) {
        if (dept.getId() == null) {
            throw new BusinessException("部门 ID 不能为空");
        }
        deptService.updateDept(dept);
        return R.ok(null, "修改成功");
    }

    @Operation(summary = "删除部门")
    @OperateLog(title = "部门管理", businessType = BusinessType.DELETE)
    @PreAuthorize("hasAuthority('sys:dept:remove')")
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        deptService.removeDept(id);
        return R.ok(null, "删除成功");
    }
}
