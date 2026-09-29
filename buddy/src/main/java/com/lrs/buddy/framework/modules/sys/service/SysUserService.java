package com.lrs.buddy.framework.modules.sys.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lrs.buddy.framework.common.model.PageResult;
import com.lrs.buddy.framework.modules.sys.entity.SysUser;
import com.lrs.buddy.framework.modules.sys.model.query.UserQuery;
import com.lrs.buddy.framework.modules.sys.model.vo.SysUserVO;

import java.util.List;

/**
 * 用户服务。
 */
public interface SysUserService extends IService<SysUser> {

    PageResult<SysUserVO> pageUsers(UserQuery query);

    /** 按用户名查询（用于登录） */
    SysUser getByUsername(String username);

    void createUser(SysUser user, List<Long> roleIds);

    void modifyUser(SysUser user, List<Long> roleIds);

    /** 批量删除（逻辑删除），不允许删除当前登录用户 */
    void removeUsers(List<Long> ids);

    void resetPassword(Long userId, String newPassword);

    List<Long> roleIdsByUserId(Long userId);

    /** 校验用户名是否可用 */
    void checkUsernameUnique(String username, Long excludeId);
}
