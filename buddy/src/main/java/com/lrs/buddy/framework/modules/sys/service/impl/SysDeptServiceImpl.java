package com.lrs.buddy.framework.modules.sys.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lrs.buddy.framework.common.exception.BusinessException;
import com.lrs.buddy.framework.common.util.TreeUtils;
import com.lrs.buddy.framework.modules.sys.entity.SysDept;
import com.lrs.buddy.framework.modules.sys.entity.SysUser;
import com.lrs.buddy.framework.modules.sys.mapper.SysDeptMapper;
import com.lrs.buddy.framework.modules.sys.mapper.SysUserMapper;
import com.lrs.buddy.framework.modules.sys.model.vo.SysDeptVO;
import com.lrs.buddy.framework.modules.sys.service.SysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SysDeptServiceImpl extends ServiceImpl<SysDeptMapper, SysDept> implements SysDeptService {

    private final SysUserMapper userMapper;

    @Override
    public List<SysDeptVO> tree() {
        List<SysDept> depts = list(new LambdaQueryWrapper<SysDept>()
                .orderByAsc(SysDept::getSort)
                .orderByAsc(SysDept::getId));
        if (CollectionUtils.isEmpty(depts)) {
            return List.of();
        }
        List<SysDeptVO> vos = depts.stream().map(this::toVO).toList();
        return TreeUtils.build(vos, SysDeptVO::getId, SysDeptVO::getParentId, SysDeptVO::getChildren, 0L);
    }

    @Override
    public void saveDept(SysDept dept) {
        // ancestors 是冗余字段，新增时必须算对，否则"本部门及以下"的数据权限会失效
        dept.setAncestors(buildAncestors(dept.getParentId()));
        save(dept);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDept(SysDept dept) {
        SysDept exist = getById(dept.getId());
        if (exist == null) {
            throw new BusinessException("部门不存在");
        }
        if (Objects.equals(dept.getParentId(), dept.getId())) {
            throw new BusinessException("上级部门不能是自己");
        }
        dept.setAncestors(buildAncestors(dept.getParentId()));
        updateById(dept);

        // 父级变了，所有子孙的 ancestors 都要跟着变——这是冗余字段的代价
        updateChildrenAncestors(dept.getId(), dept.getAncestors());
    }

    @Override
    public void removeDept(Long deptId) {
        long childCount = count(new LambdaQueryWrapper<SysDept>().eq(SysDept::getParentId, deptId));
        if (childCount > 0) {
            throw new BusinessException("存在下级部门，请先删除下级");
        }
        long userCount = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getDeptId, deptId));
        if (userCount > 0) {
            throw new BusinessException("该部门下还有 " + userCount + " 个用户，不能删除");
        }
        removeById(deptId);
    }

    /**
     * 拼接祖级链。
     * 根部门的 ancestors 是 "0"，一级部门是 "0,100"，以此类推。
     */
    private String buildAncestors(Long parentId) {
        if (parentId == null || parentId == 0L) {
            return "0";
        }
        SysDept parent = getById(parentId);
        if (parent == null) {
            return "0";
        }
        String parentAncestors = parent.getAncestors() == null ? "0" : parent.getAncestors();
        return parentAncestors + "," + parentId;
    }

    /**
     * 递归更新子孙的祖级链。
     *
     * <p>部门层级通常不超过 5 层、每层的子部门数量有限，
     * 这里的递归深度和总记录数都可控。
     */
    private void updateChildrenAncestors(Long parentId, String parentAncestors) {
        List<SysDept> children = list(new LambdaQueryWrapper<SysDept>().eq(SysDept::getParentId, parentId));
        for (SysDept child : children) {
            String ancestors = parentAncestors + "," + parentId;
            SysDept update = new SysDept();
            update.setId(child.getId());
            update.setAncestors(ancestors);
            updateById(update);
            updateChildrenAncestors(child.getId(), ancestors);
        }
    }

    private SysDeptVO toVO(SysDept dept) {
        SysDeptVO vo = new SysDeptVO();
        BeanUtils.copyProperties(dept, vo);
        return vo;
    }
}
