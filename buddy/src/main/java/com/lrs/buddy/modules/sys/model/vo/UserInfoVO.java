package com.lrs.buddy.modules.sys.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 当前登录用户信息，前端登录后第一次请求拿到它用于渲染头像、菜单和权限判断。
 */
@Data
@Builder
public class UserInfoVO {

    private Long userId;
    private String username;
    private String nickname;
    private String avatar;

    /** 角色标识，如 ["admin"] */
    private List<String> roles;

    /** 权限标识，如 ["sys:user:list", "sys:user:save"] */
    private List<String> permissions;

    private Boolean superAdmin;
}
