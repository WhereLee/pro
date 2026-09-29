package com.lrs.buddy.framework.modules.sys.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lrs.buddy.framework.common.exception.BusinessException;
import com.lrs.buddy.framework.common.util.TreeUtils;
import com.lrs.buddy.framework.modules.sys.entity.SysMenu;
import com.lrs.buddy.framework.modules.sys.entity.SysRoleMenu;
import com.lrs.buddy.framework.modules.sys.mapper.SysMenuMapper;
import com.lrs.buddy.framework.modules.sys.mapper.SysRoleMenuMapper;
import com.lrs.buddy.framework.modules.sys.model.vo.SysMenuVO;
import com.lrs.buddy.framework.modules.sys.service.SysMenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements SysMenuService {

    private final SysRoleMenuMapper roleMenuMapper;

    @Override
    public List<SysMenuVO> tree() {
        List<SysMenu> menus = list(Wrappers.<SysMenu>lambdaQuery()
                .orderByAsc(SysMenu::getSort)
                .orderByAsc(SysMenu::getId));
        return buildTree(menus);
    }

    @Override
    public List<SysMenuVO> userRoutes(Long userId, boolean superAdmin) {
        List<SysMenu> menus = baseMapper.selectMenusByUserId(userId, superAdmin);
        return buildTree(menus);
    }

    @Override
    public List<String> userPerms(Long userId, boolean superAdmin) {
        return baseMapper.selectPermsByUserId(userId, superAdmin);
    }

    @Override
    public List<Long> menuIdsByRoleId(Long roleId) {
        return roleMenuMapper.selectList(Wrappers.<SysRoleMenu>lambdaQuery()
                        .eq(SysRoleMenu::getRoleId, roleId))
                .stream()
                .map(SysRoleMenu::getMenuId)
                .toList();
    }

    @Override
    public void createMenu(SysMenu menu) {
        validateParent(menu.getParentId(), menu.getId());
        save(menu);
    }

    @Override
    public void modifyMenu(SysMenu menu) {
        SysMenu exist = getById(menu.getId());
        if (exist == null) {
            throw new BusinessException("菜单不存在");
        }
        // 不允许把菜单的父级改成它自己或其子孙，否则树会出现环
        if (Objects.equals(menu.getParentId(), menu.getId())) {
            throw new BusinessException("上级菜单不能是自己");
        }
        updateById(menu);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMenu(Long menuId) {
        long childCount = count(Wrappers.<SysMenu>lambdaQuery().eq(SysMenu::getParentId, menuId));
        if (childCount > 0) {
            throw new BusinessException("存在子菜单，请先删除子菜单");
        }
        removeById(menuId);
        roleMenuMapper.deleteByMenuId(menuId);
    }

    private void validateParent(Long parentId, Long selfId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        SysMenu parent = getById(parentId);
        if (parent == null) {
            throw new BusinessException("上级菜单不存在");
        }
        if (Objects.equals(parentId, selfId)) {
            throw new BusinessException("上级菜单不能是自己");
        }
    }

    /**
     * 菜单可能非常多，但树的深度有限，因此采用"一次性查出 + 内存组装"，
     * 避免递归查库产生 N+1 问题。
     */
    private List<SysMenuVO> buildTree(List<SysMenu> menus) {
        if (CollectionUtils.isEmpty(menus)) {
            return List.of();
        }
        List<SysMenuVO> vos = menus.stream().map(this::toVO).toList();
        return TreeUtils.build(vos, SysMenuVO::getId, SysMenuVO::getParentId, SysMenuVO::getChildren, 0L);
    }

    private SysMenuVO toVO(SysMenu menu) {
        SysMenuVO vo = new SysMenuVO();
        BeanUtils.copyProperties(menu, vo);
        return vo;
    }
}
