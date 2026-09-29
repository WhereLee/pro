package com.lrs.buddy.framework.modules.sys.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lrs.buddy.framework.common.exception.BusinessException;
import com.lrs.buddy.framework.common.model.PageResult;
import com.lrs.buddy.framework.modules.sys.entity.SysRole;
import com.lrs.buddy.framework.modules.sys.entity.SysRoleMenu;
import com.lrs.buddy.framework.modules.sys.mapper.SysRoleMapper;
import com.lrs.buddy.framework.modules.sys.mapper.SysRoleMenuMapper;
import com.lrs.buddy.framework.modules.sys.model.query.RoleQuery;
import com.lrs.buddy.framework.modules.sys.model.vo.SysRoleVO;
import com.lrs.buddy.framework.modules.sys.service.SysMenuService;
import com.lrs.buddy.framework.modules.sys.service.SysRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuService menuService;

    @Override
    public PageResult<SysRoleVO> pageRoles(RoleQuery query) {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<SysRole>()
                .like(StringUtils.hasText(query.getRoleName()), SysRole::getRoleName, query.getRoleName())
                .like(StringUtils.hasText(query.getRoleKey()), SysRole::getRoleKey, query.getRoleKey())
                .eq(Objects.nonNull(query.getStatus()), SysRole::getStatus, query.getStatus())
                .orderByAsc(SysRole::getSort);

        IPage<SysRole> page = page(query.toPage(), wrapper);
        // 分页结果转换时补上每个角色已分配的菜单
        return PageResult.of(page, role -> {
            SysRoleVO vo = new SysRoleVO();
            BeanUtils.copyProperties(role, vo);
            vo.setMenuIds(menuService.menuIdsByRoleId(role.getId()));
            return vo;
        });
    }

    @Override
    public List<SysRoleVO> listAll() {
        return list().stream().map(role -> {
            SysRoleVO vo = new SysRoleVO();
            BeanUtils.copyProperties(role, vo);
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createRole(SysRole role, List<Long> menuIds) {
        checkRoleKeyUnique(role.getRoleKey(), null);
        save(role);
        bindMenus(role.getId(), menuIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void modifyRole(SysRole role, List<Long> menuIds) {
        SysRole exist = getById(role.getId());
        if (exist == null) {
            throw new BusinessException("角色不存在");
        }
        checkRoleKeyUnique(role.getRoleKey(), role.getId());
        updateById(role);
        if (menuIds != null) {
            bindMenus(role.getId(), menuIds);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeRoles(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        for (Long id : ids) {
            long used = baseMapper.countUserByRoleId(id);
            if (used > 0) {
                SysRole role = getById(id);
                throw new BusinessException("角色【" + (role == null ? id : role.getRoleName())
                        + "】已被 " + used + " 个用户使用，不能删除");
            }
        }
        removeByIds(ids);
        ids.forEach(roleMenuMapper::deleteByRoleId);
    }

    @Override
    public List<SysRole> rolesByUserId(Long userId) {
        return baseMapper.selectRolesByUserId(userId);
    }

    @Override
    public List<String> roleKeysByUserId(Long userId) {
        return rolesByUserId(userId).stream().map(SysRole::getRoleKey).toList();
    }

    private void checkRoleKeyUnique(String roleKey, Long excludeId) {
        if (!StringUtils.hasText(roleKey)) {
            return;
        }
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleKey, roleKey);
        if (excludeId != null) {
            wrapper.ne(SysRole::getId, excludeId);
        }
        if (count(wrapper) > 0) {
            throw new BusinessException("角色标识【" + roleKey + "】已存在");
        }
    }

    /**
     * 重新绑定菜单：先清后插。
     *
     * <p>为什么不逐条 diff 出"新增/删除"：菜单数量有限（几十到几百），
     * 全量替换的代码更简单且不易出错；真正需要 diff 的是数据量大的关联关系。
     */
    private void bindMenus(Long roleId, List<Long> menuIds) {
        roleMenuMapper.deleteByRoleId(roleId);
        if (CollectionUtils.isEmpty(menuIds)) {
            return;
        }
        List<SysRoleMenu> bindings = new ArrayList<>(menuIds.size());
        for (Long menuId : menuIds) {
            SysRoleMenu binding = new SysRoleMenu();
            binding.setRoleId(roleId);
            binding.setMenuId(menuId);
            bindings.add(binding);
        }
        // 批量插入，避免 N 次单条 insert 的网络往返
        roleMenuMapper.insertBatch(bindings);
    }
}
