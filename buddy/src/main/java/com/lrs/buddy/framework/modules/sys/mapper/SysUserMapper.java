package com.lrs.buddy.framework.modules.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrs.buddy.framework.modules.sys.entity.SysUser;
import com.lrs.buddy.framework.modules.sys.model.query.UserQuery;
import com.lrs.buddy.framework.modules.sys.model.vo.SysUserVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Param;

public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按用户名查询（含已停用账号，用于登录时给出明确提示）。
     */
    SysUser selectByUsername(@Param("username") String username);

    /**
     * 用户分页查询（联部门表，并拼接数据权限过滤片段）。
     *
     * <p>为什么自定义 SQL 而不用 MP 的 lambda 分页：
     * 数据权限需要联 sys_dept 并在 WHERE 里追加动态片段，
     * 用 Wrapper 表达不了这种"带别名的联表 + 任意 SQL 片段"的组合。
     */
    IPage<SysUserVO> selectUserPage(IPage<SysUserVO> page, @Param("query") UserQuery query);
}
