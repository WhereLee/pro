package com.lrs.buddy.common.aspect;

import com.lrs.buddy.common.annotation.DataScope;
import com.lrs.buddy.common.enums.DataScopeType;
import com.lrs.buddy.common.model.DataScopeQuery;
import com.lrs.buddy.modules.sys.entity.SysRole;
import com.lrs.buddy.modules.sys.service.SysRoleService;
import com.lrs.buddy.security.LoginUser;
import com.lrs.buddy.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 数据权限切面。
 *
 * <p>执行时机是 {@code @Before}：必须在业务方法读取查询条件之前把过滤片段塞进去，
 * 否则 Service 拿到的是没有数据权限约束的条件。
 *
 * <p>生成的是 SQL 片段而非"查出数据再在内存里过滤"——
 * 后者在数据量稍大时就要把全表载入内存，既慢又容易 OOM。
 */
@Slf4j
@Aspect
@Component
@Order(50)
@RequiredArgsConstructor
public class DataScopeAspect {

    private final SysRoleService roleService;

    @Before("@annotation(dataScope)")
    public void before(JoinPoint point, DataScope dataScope) {
        LoginUser user = SecurityUtils.getLoginUser();
        // 未登录（如定时任务）或超级管理员：不做数据过滤
        if (user == null || Boolean.TRUE.equals(user.getSuperAdmin())) {
            return;
        }

        DataScopeQuery query = findQueryParam(point.getArgs());
        if (query == null) {
            log.warn("方法 {} 标注了 @DataScope，但参数中没有 DataScopeQuery，已跳过",
                    point.getSignature().toShortString());
            return;
        }

        query.setSqlFilter(buildSqlFilter(dataScope, user));
    }

    private String buildSqlFilter(DataScope dataScope, LoginUser user) {
        DataScopeType type = resolveScopeType(user.getUserId());
        String deptAlias = dataScope.deptAlias();
        String userAlias = dataScope.userAlias();

        return switch (type) {
            case ALL -> "";
            case CUSTOM -> " AND " + deptAlias + ".dept_id IN ("
                    + "SELECT rd.dept_id FROM sys_role_dept rd "
                    + "INNER JOIN sys_user_role ur ON rd.role_id = ur.role_id "
                    + "WHERE ur.user_id = " + user.getUserId() + ")";
            case DEPT -> " AND " + deptAlias + ".dept_id = " + user.getDeptId();
            case DEPT_AND_CHILD -> " AND " + deptAlias + ".dept_id IN ("
                    + "SELECT dept_id FROM sys_dept WHERE dept_id = " + user.getDeptId()
                    + " OR ancestors LIKE '%," + user.getDeptId() + ",%')";
            case SELF -> " AND " + userAlias + ".id = " + user.getUserId();
        };
    }

    /**
     * 取用户所有角色中最宽松的数据范围。
     *
     * <p>没有角色时返回 {@code SELF}：这是最小权限原则的默认兜底，
     * 避免"忘了配角色"意外变成"能看到全部数据"。
     */
    private DataScopeType resolveScopeType(Long userId) {
        List<SysRole> roles = roleService.rolesByUserId(userId);
        if (roles.isEmpty()) {
            return DataScopeType.SELF;
        }
        int min = roles.stream()
                .map(SysRole::getDataScope)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .min()
                .orElse(DataScopeType.SELF.getCode());
        return DataScopeType.of(min);
    }

    private DataScopeQuery findQueryParam(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof DataScopeQuery query) {
                return query;
            }
        }
        return null;
    }
}
