package com.lrs.buddy.framework.modules.sys.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lrs.buddy.framework.common.exception.BusinessException;
import com.lrs.buddy.framework.common.annotation.DataScope;
import com.lrs.buddy.framework.common.model.PageResult;
import com.lrs.buddy.framework.modules.sys.entity.SysUser;
import com.lrs.buddy.framework.modules.sys.entity.SysUserRole;
import com.lrs.buddy.framework.modules.sys.mapper.SysUserMapper;
import com.lrs.buddy.framework.modules.sys.mapper.SysUserRoleMapper;
import com.lrs.buddy.framework.modules.sys.model.query.UserQuery;
import com.lrs.buddy.framework.modules.sys.model.vo.SysUserVO;
import com.lrs.buddy.framework.modules.sys.service.SysUserService;
import com.lrs.buddy.framework.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    @DataScope(deptAlias = "d", userAlias = "u")
    public PageResult<SysUserVO> pageUsers(UserQuery query) {
        // sqlFilter 已由 DataScopeAspect 写入，XML 里用 ${query.sqlFilter} 拼接
        IPage<SysUserVO> page = baseMapper.selectUserPage(query.toPage(), query);
        // 角色是独立关联，逐条补上；用户列表每页最多几十条，开销可接受
        for (SysUserVO vo : page.getRecords()) {
            vo.setRoleIds(roleIdsByUserId(vo.getId()));
        }
        return PageResult.of(page);
    }

    @Override
    public SysUser getByUsername(String username) {
        return baseMapper.selectByUsername(username);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createUser(SysUser user, List<Long> roleIds) {
        checkUsernameUnique(user.getUsername(), null);
        // 密码以 BCrypt 密文保存，明文不落库
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        save(user);
        bindRoles(user.getId(), roleIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void modifyUser(SysUser user, List<Long> roleIds) {
        SysUser exist = getById(user.getId());
        if (exist == null) {
            throw new BusinessException("用户不存在");
        }
        if (StringUtils.hasText(user.getUsername())) {
            checkUsernameUnique(user.getUsername(), user.getId());
        }
        // password 字段不在这里修改，改密码走 resetPassword，避免把密文覆盖成明文
        user.setPassword(null);
        updateById(user);
        if (roleIds != null) {
            bindRoles(user.getId(), roleIds);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeUsers(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        Long currentUserId = UserContext.getUserId();
        if (currentUserId != null && ids.contains(currentUserId)) {
            throw new BusinessException("不能删除当前登录的用户");
        }
        removeByIds(ids);
        ids.forEach(userRoleMapper::deleteByUserId);
    }

    @Override
    public void resetPassword(Long userId, String newPassword) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(newPassword));
        updateById(update);
    }

    @Override
    public List<Long> roleIdsByUserId(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .toList();
    }

    @Override
    public void checkUsernameUnique(String username, Long excludeId) {
        if (!StringUtils.hasText(username)) {
            throw new BusinessException("用户名不能为空");
        }
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username);
        if (excludeId != null) {
            wrapper.ne(SysUser::getId, excludeId);
        }
        if (count(wrapper) > 0) {
            throw new BusinessException("用户名【" + username + "】已存在");
        }
    }

    private void bindRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.deleteByUserId(userId);
        if (CollectionUtils.isEmpty(roleIds)) {
            return;
        }
        List<SysUserRole> bindings = new ArrayList<>(roleIds.size());
        for (Long roleId : roleIds) {
            SysUserRole binding = new SysUserRole();
            binding.setUserId(userId);
            binding.setRoleId(roleId);
            bindings.add(binding);
        }
        userRoleMapper.insertBatch(bindings);
    }
}
