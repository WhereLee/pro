package com.lrs.buddy.modules.sys.controller;

import com.lrs.buddy.common.BusinessException;
import com.lrs.buddy.common.PageResult;
import com.lrs.buddy.common.R;
import com.lrs.buddy.common.annotation.RepeatSubmit;
import com.lrs.buddy.modules.log.annotation.OperateLog;
import com.lrs.buddy.modules.log.enums.BusinessType;
import com.lrs.buddy.modules.sys.entity.SysRole;
import com.lrs.buddy.modules.sys.model.form.SysRoleForm;
import com.lrs.buddy.modules.sys.model.query.RoleQuery;
import com.lrs.buddy.modules.sys.model.vo.SysRoleVO;
import com.lrs.buddy.modules.sys.service.SysMenuService;
import com.lrs.buddy.modules.sys.service.SysRoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
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
 * 角色管理。
 */
@Tag(name = "角色管理")
@RestController
@RequestMapping("/sys/role")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleService roleService;
    private final SysMenuService menuService;

    @Operation(summary = "角色分页列表")
    @PreAuthorize("hasAuthority('sys:role:list')")
    @PostMapping("/page")
    public R<PageResult<SysRoleVO>> page(@Valid @RequestBody RoleQuery query) {
        return R.ok(roleService.pageRoles(query));
    }

    @Operation(summary = "全部角色（下拉选择用）")
    @PreAuthorize("hasAuthority('sys:role:list')")
    @GetMapping("/list")
    public R<List<SysRoleVO>> list() {
        return R.ok(roleService.listAll());
    }

    @Operation(summary = "角色已分配的菜单 ID")
    @PreAuthorize("hasAuthority('sys:role:list')")
    @GetMapping("/menu/{roleId}")
    public R<List<Long>> roleMenus(@PathVariable Long roleId) {
        return R.ok(menuService.menuIdsByRoleId(roleId));
    }

    @Operation(summary = "新增角色")
    @OperateLog(title = "角色管理", businessType = BusinessType.INSERT)
    @RepeatSubmit(interval = 3000)
    @PreAuthorize("hasAuthority('sys:role:save')")
    @PostMapping
    public R<Void> save(@Valid @RequestBody SysRoleForm form) {
        roleService.createRole(toEntity(form), form.getMenuIds());
        return R.ok(null, "新增成功");
    }

    @Operation(summary = "修改角色")
    @OperateLog(title = "角色管理", businessType = BusinessType.UPDATE)
    @PreAuthorize("hasAuthority('sys:role:update')")
    @PutMapping
    public R<Void> update(@Valid @RequestBody SysRoleForm form) {
        if (form.getId() == null) {
            throw new BusinessException("角色 ID 不能为空");
        }
        roleService.modifyRole(toEntity(form), form.getMenuIds());
        return R.ok(null, "修改成功");
    }

    /**
     * 分配权限单独用 GRANT 类型：
     * 改权限比改资料敏感得多，日志里要能一眼区分出来。
     */
    @Operation(summary = "分配角色权限")
    @OperateLog(title = "角色管理", businessType = BusinessType.GRANT)
    @PreAuthorize("hasAuthority('sys:role:update')")
    @PutMapping("/grant")
    public R<Void> grant(@RequestBody SysRoleForm form) {
        if (form.getId() == null) {
            throw new BusinessException("角色 ID 不能为空");
        }
        SysRole role = new SysRole();
        role.setId(form.getId());
        roleService.modifyRole(role, form.getMenuIds());
        return R.ok(null, "权限已更新");
    }

    @Operation(summary = "删除角色（支持批量）")
    @OperateLog(title = "角色管理", businessType = BusinessType.DELETE)
    @PreAuthorize("hasAuthority('sys:role:remove')")
    @DeleteMapping
    public R<Void> remove(@RequestBody List<Long> ids) {
        roleService.removeRoles(ids);
        return R.ok(null, "删除成功");
    }

    private SysRole toEntity(SysRoleForm form) {
        SysRole role = new SysRole();
        BeanUtils.copyProperties(form, role);
        return role;
    }
}
