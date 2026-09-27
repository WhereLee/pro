package com.lrs.buddy.modules.sys.model.query;

import com.lrs.buddy.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色分页查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RoleQuery extends PageQuery {

    private String roleName;

    private String roleKey;

    private Integer status;
}
