package com.lrs.buddy.modules.sys.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lrs.buddy.modules.sys.entity.SysMenu;
import com.lrs.buddy.modules.sys.model.vo.SysMenuVO;

import java.util.List;

/**
 * 菜单服务。
 */
public interface SysMenuService extends IService<SysMenu> {

    /** 全部菜单树（角色分配权限时使用） */
    List<SysMenuVO> tree();

    /** 指定用户可见的菜单树（前端动态路由的数据源） */
    List<SysMenuVO> userRoutes(Long userId, boolean superAdmin);

    /** 指定用户拥有的权限标识 */
    List<String> userPerms(Long userId, boolean superAdmin);

    /** 指定角色已分配的菜单 ID */
    List<Long> menuIdsByRoleId(Long roleId);

    void createMenu(SysMenu menu);

    void modifyMenu(SysMenu menu);

    /** 删除菜单：存在子菜单时拒绝，并同步清理角色绑定 */
    void removeMenu(Long menuId);
}
