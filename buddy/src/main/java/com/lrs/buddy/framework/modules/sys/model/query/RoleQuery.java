package com.lrs.buddy.framework.modules.sys.model.query;

import com.lrs.buddy.framework.common.model.PageQuery;
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
