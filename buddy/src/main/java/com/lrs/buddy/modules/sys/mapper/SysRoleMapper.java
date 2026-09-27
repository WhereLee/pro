package com.lrs.buddy.modules.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrs.buddy.modules.sys.entity.SysRole;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 查询指定用户的角色列表。
     */
    List<SysRole> selectRolesByUserId(@Param("userId") Long userId);

    /**
     * 查询某角色被多少用户使用。
     */
    long countUserByRoleId(@Param("roleId") Long roleId);
}
