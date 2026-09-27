package com.lrs.buddy.modules.sys.controller;

import com.lrs.buddy.common.BusinessException;
import com.lrs.buddy.common.PageResult;
import com.lrs.buddy.common.R;
import com.lrs.buddy.common.annotation.RepeatSubmit;
import com.lrs.buddy.modules.log.annotation.OperateLog;
import com.lrs.buddy.modules.log.enums.BusinessType;
import com.lrs.buddy.modules.sys.entity.SysUser;
import com.lrs.buddy.modules.sys.model.form.ResetPasswordForm;
import com.lrs.buddy.modules.sys.model.form.SysUserForm;
import com.lrs.buddy.modules.sys.model.query.UserQuery;
import com.lrs.buddy.modules.sys.model.vo.SysUserVO;
import com.lrs.buddy.modules.sys.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
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
 * 用户管理。
 *
 * <p>权限控制使用 {@code @PreAuthorize} + 权限标识。权限标识与菜单表的
 * {@code perms} 字段一致——前端据此控制按钮是否渲染，后端据此做接口鉴权，
 * 两端共用同一份配置，不会出现"按钮能点但接口报 403"的割裂。
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/sys/user")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService userService;

    @Operation(summary = "用户分页列表")
    @PreAuthorize("hasAuthority('sys:user:list')")
    @PostMapping("/page")
    public R<PageResult<SysUserVO>> page(@Valid @RequestBody UserQuery query) {
        return R.ok(userService.pageUsers(query));
    }

    @Operation(summary = "用户详情")
    @PreAuthorize("hasAuthority('sys:user:query')")
    @GetMapping("/{id}")
    public R<SysUserVO> detail(@PathVariable Long id) {
        SysUser user = userService.getById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        SysUserVO vo = new SysUserVO();
        BeanUtils.copyProperties(user, vo);
        vo.setRoleIds(userService.roleIdsByUserId(id));
        return R.ok(vo);
    }

    @Operation(summary = "新增用户")
    @OperateLog(title = "用户管理", businessType = BusinessType.INSERT)
    @RepeatSubmit(interval = 3000)
    @PreAuthorize("hasAuthority('sys:user:save')")
    @PostMapping
    public R<Void> save(@Valid @RequestBody SysUserForm form) {
        if (!StringUtils.hasText(form.getPassword())) {
            throw new BusinessException("新增用户时密码不能为空");
        }
        userService.createUser(toEntity(form), form.getRoleIds());
        return R.ok(null, "新增成功");
    }

    @Operation(summary = "修改用户")
    @OperateLog(title = "用户管理", businessType = BusinessType.UPDATE)
    @PreAuthorize("hasAuthority('sys:user:update')")
    @PutMapping
    public R<Void> update(@Valid @RequestBody SysUserForm form) {
        if (form.getId() == null) {
            throw new BusinessException("用户 ID 不能为空");
        }
        // 密码留空表示不修改：modifyUser 内部不会覆盖密文
        userService.modifyUser(toEntity(form), form.getRoleIds());
        return R.ok(null, "修改成功");
    }

    @Operation(summary = "删除用户（支持批量）")
    @OperateLog(title = "用户管理", businessType = BusinessType.DELETE)
    @PreAuthorize("hasAuthority('sys:user:remove')")
    @DeleteMapping
    public R<Void> remove(@RequestBody List<Long> ids) {
        userService.removeUsers(ids);
        return R.ok(null, "删除成功");
    }

    /**
     * 重置密码。
     *
     * <p>{@code logParam = false}：请求体里是新密码，
     * 绝不能把密码明文写进日志表，否则日志就变成了密码泄露源。
     */
    @Operation(summary = "重置密码")
    @OperateLog(title = "用户管理", businessType = BusinessType.GRANT, logParam = false, logResult = false)
    @PreAuthorize("hasAuthority('sys:user:resetPwd')")
    @PutMapping("/reset-password")
    public R<Void> resetPassword(@Valid @RequestBody ResetPasswordForm form) {
        userService.resetPassword(form.getUserId(), form.getPassword());
        return R.ok(null, "密码已重置");
    }

    private SysUser toEntity(SysUserForm form) {
        SysUser user = new SysUser();
        BeanUtils.copyProperties(form, user);
        return user;
    }
}
