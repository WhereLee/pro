package com.lrs.buddy.modules.sys.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lrs.buddy.common.PageResult;
import com.lrs.buddy.modules.sys.entity.SysRole;
import com.lrs.buddy.modules.sys.model.query.RoleQuery;
import com.lrs.buddy.modules.sys.model.vo.SysRoleVO;

import java.util.List;

/**
 * 角色服务。
 */
public interface SysRoleService extends IService<SysRole> {

    PageResult<SysRoleVO> pageRoles(RoleQuery query);

    List<SysRoleVO> listAll();

    void createRole(SysRole role, List<Long> menuIds);

    void modifyRole(SysRole role, List<Long> menuIds);

    /** 批量删除：被用户占用的角色拒绝删除 */
    void removeRoles(List<Long> ids);

    List<SysRole> rolesByUserId(Long userId);

    List<String> roleKeysByUserId(Long userId);
}
