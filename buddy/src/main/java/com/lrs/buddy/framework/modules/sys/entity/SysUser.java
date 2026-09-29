package com.lrs.buddy.framework.modules.sys.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.framework.common.model.BaseEntity;
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

    /**
     * 所属租户。仅读取（登录时解析用户租户）；写入交给多租户拦截器/DB 默认值：
     * insert/update 策略设为 NEVER，避免与 TenantLineInnerInterceptor 注入的 tenant_id 冲突；
     * 单租户（拦截器未装配）时该列取 DB 默认值 1。
     */
    @TableField(value = "tenant_id", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long tenantId;
}
