package com.lrs.buddy.framework.modules.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrs.buddy.framework.modules.sys.entity.SysUserRole;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    /**
     * 删除用户的全部角色绑定。
     */
    int deleteByUserId(@Param("userId") Long userId);

    /**
     * 删除角色的全部用户绑定。
     */
    int deleteByRoleId(@Param("roleId") Long roleId);

    /**
     * 批量插入绑定关系。
     */
    int insertBatch(@Param("list") List<SysUserRole> list);
}
