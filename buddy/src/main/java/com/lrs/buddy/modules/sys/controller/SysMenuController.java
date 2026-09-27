package com.lrs.buddy.modules.sys.controller;

import com.lrs.buddy.common.BusinessException;
import com.lrs.buddy.common.R;
import com.lrs.buddy.common.annotation.RepeatSubmit;
import com.lrs.buddy.modules.log.annotation.OperateLog;
import com.lrs.buddy.modules.log.enums.BusinessType;
import com.lrs.buddy.modules.sys.entity.SysMenu;
import com.lrs.buddy.modules.sys.model.vo.SysMenuVO;
import com.lrs.buddy.modules.sys.service.SysMenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * 菜单（与按钮权限）管理。
 */
@Tag(name = "菜单管理")
@RestController
@RequestMapping("/sys/menu")
@RequiredArgsConstructor
public class SysMenuController {

    private final SysMenuService menuService;

    @Operation(summary = "菜单树（角色授权时使用）")
    @PreAuthorize("hasAuthority('sys:menu:list')")
    @GetMapping("/tree")
    public R<List<SysMenuVO>> tree() {
        return R.ok(menuService.tree());
    }

    @Operation(summary = "新增菜单")
    @OperateLog(title = "菜单管理", businessType = BusinessType.INSERT)
    @RepeatSubmit(interval = 3000)
    @PreAuthorize("hasAuthority('sys:menu:save')")
    @PostMapping
    public R<Void> save(@Valid @RequestBody SysMenu menu) {
        menuService.createMenu(menu);
        return R.ok(null, "新增成功");
    }

    @Operation(summary = "修改菜单")
    @OperateLog(title = "菜单管理", businessType = BusinessType.UPDATE)
    @PreAuthorize("hasAuthority('sys:menu:update')")
    @PutMapping
    public R<Void> update(@Valid @RequestBody SysMenu menu) {
        if (menu.getId() == null) {
            throw new BusinessException("菜单 ID 不能为空");
        }
        menuService.modifyMenu(menu);
        return R.ok(null, "修改成功");
    }

    @Operation(summary = "删除菜单（存在子菜单时拒绝）")
    @OperateLog(title = "菜单管理", businessType = BusinessType.DELETE)
    @PreAuthorize("hasAuthority('sys:menu:remove')")
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        menuService.removeMenu(id);
        return R.ok(null, "删除成功");
    }
}
