package com.lrs.buddy.modules.sys.model.query;

import com.lrs.buddy.common.model.DataScopeQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户分页查询条件。
 *
 * <p>继承 {@link DataScopeQuery} 而不是 {@code PageQuery}：
 * 用户列表需要按当前登录人的角色做数据范围过滤。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQuery extends DataScopeQuery {

    private String username;

    private String nickname;

    private String phone;

    /** 状态：0 正常，1 停用 */
    private Integer status;

    /** 按部门筛选 */
    private Long deptId;
}
