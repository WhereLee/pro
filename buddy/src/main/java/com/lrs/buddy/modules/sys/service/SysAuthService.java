package com.lrs.buddy.modules.sys.service;

import com.lrs.buddy.modules.sys.model.form.LoginForm;
import com.lrs.buddy.modules.sys.model.vo.LoginVO;
import com.lrs.buddy.modules.sys.model.vo.SysMenuVO;
import com.lrs.buddy.modules.sys.model.vo.UserInfoVO;

import java.util.List;

/**
 * 认证服务。
 */
public interface SysAuthService {

    /**
     * 登录。
     *
     * @param form      登录参数
     * @param ip        客户端 IP，写入在线台账
     * @param userAgent 浏览器标识
     */
    LoginVO login(LoginForm form, String ip, String userAgent);

    /** 登出：销毁当前会话 */
    void logout();

    /** 当前登录用户的资料、角色与权限 */
    UserInfoVO currentUser();

    /** 当前登录用户可见的菜单树（前端动态路由数据源） */
    List<SysMenuVO> currentRoutes();
}
