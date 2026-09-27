package com.lrs.buddy.modules.sys.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统用户。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private String username;

    /** BCrypt 密文，明文永不落库 */
    private String password;

    private String nickname;

    private String email;

    private String phone;

    private String avatar;

    /** 所属部门，数据权限过滤的依据 */
    private Long deptId;

    /** 状态：0 正常，1 停用 */
    private Integer status;
}
