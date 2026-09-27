package com.lrs.buddy.modules.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrs.buddy.modules.sys.entity.SysMenu;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysMenuMapper extends BaseMapper<SysMenu> {

    /**
     * 查询某用户拥有的全部菜单（去重）。
     *
     * <p>超级管理员返回全部，普通用户按 用户→角色→菜单 三表关联查询。
     */
    List<SysMenu> selectMenusByUserId(@Param("userId") Long userId, @Param("superAdmin") boolean superAdmin);

    /**
     * 查询某用户拥有的全部权限标识（去重、过滤空值）。
     */
    List<String> selectPermsByUserId(@Param("userId") Long userId, @Param("superAdmin") boolean superAdmin);
}
