package com.lrs.buddy.modules.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrs.buddy.modules.sys.entity.SysRoleMenu;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysRoleMenuMapper extends BaseMapper<SysRoleMenu> {

    /**
     * 删除角色的全部菜单绑定。
     */
    int deleteByRoleId(@Param("roleId") Long roleId);

    /**
     * 删除指定菜单在所有角色中的绑定（删除菜单时调用）。
     */
    int deleteByMenuId(@Param("menuId") Long menuId);

    /**
     * 批量插入绑定关系。
     *
     * <p>不用循环单条 insert：一条 SQL 完成，减少网络往返，
     * 在大批量授权时差距非常明显。
     */
    int insertBatch(@Param("list") List<SysRoleMenu> list);
}
