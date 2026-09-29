package com.lrs.buddy.framework.modules.sys.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.framework.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色。
 *
 * <p>{@code roleKey} 是角色的英文标识（如 admin、guest），
 * 业务代码里应依赖它而不是 roleId——因为 id 在不同环境（开发/生产）可能不同，
 * 而 roleKey 是稳定的。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {

    private String roleName;

    private String roleKey;

    private Integer sort;

    /**
     * 数据范围，见 {@link com.lrs.buddy.framework.common.enums.DataScopeType}：
     * 1 全部 / 2 自定义 / 3 本部门 / 4 本部门及以下 / 5 仅本人
     */
    private Integer dataScope;

    /** 状态：0 正常，1 停用 */
    private Integer status;
}
