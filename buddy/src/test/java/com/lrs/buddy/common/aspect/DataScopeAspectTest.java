package com.lrs.buddy.common.aspect;

import com.lrs.buddy.common.annotation.DataScope;
import com.lrs.buddy.common.enums.DataScopeType;
import com.lrs.buddy.common.model.DataScopeQuery;
import com.lrs.buddy.modules.sys.entity.SysRole;
import com.lrs.buddy.modules.sys.service.SysRoleService;
import com.lrs.buddy.security.LoginUser;
import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据权限切面单测：用 Mockito 隔离 DB，直接验证"角色数据范围 → SQL 过滤片段"的生成逻辑。
 *
 * <p>这里刻意不起 Spring 上下文：{@code buildSqlFilter} 是纯函数式的字符串装配，
 * 单测更快也更聚焦。真实链路由 {@code SysUserApiTest} 的分页用例间接覆盖。
 */
class DataScopeAspectTest {

    private final SysRoleService roleService = mock(SysRoleService.class);
    private final DataScopeAspect aspect = new DataScopeAspect(roleService);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @DataScope(deptAlias = "d", userAlias = "u")
    void annotated() {
    }

    private DataScope dataScope() throws NoSuchMethodException {
        return DataScopeAspectTest.class.getDeclaredMethod("annotated").getAnnotation(DataScope.class);
    }

    private void loginAs(Long userId, Long deptId, boolean superAdmin) {
        LoginUser user = LoginUser.builder()
                .userId(userId).username("u" + userId).deptId(deptId).superAdmin(superAdmin)
                .permissions(java.util.Set.of()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private SysRole roleWithDataScope(int code) {
        SysRole r = new SysRole();
        r.setDataScope(code);
        return r;
    }

    private JoinPoint pointWith(DataScopeQuery query) {
        JoinPoint point = mock(JoinPoint.class);
        when(point.getArgs()).thenReturn(new Object[]{query});
        return point;
    }

    @Test
    @DisplayName("本部门：按 deptAlias 拼 dept_id = 当前用户部门")
    void deptScope() throws Exception {
        loginAs(2L, 100L, false);
        when(roleService.rolesByUserId(2L)).thenReturn(List.of(roleWithDataScope(DataScopeType.DEPT.getCode())));
        DataScopeQuery query = new DataScopeQuery();
        aspect.before(pointWith(query), dataScope());
        assertEquals(" AND d.dept_id = 100", query.getSqlFilter());
    }

    @Test
    @DisplayName("全部数据：过滤片段为空串（不加约束）")
    void allScope() throws Exception {
        loginAs(3L, 100L, false);
        when(roleService.rolesByUserId(3L)).thenReturn(List.of(roleWithDataScope(DataScopeType.ALL.getCode())));
        DataScopeQuery query = new DataScopeQuery();
        aspect.before(pointWith(query), dataScope());
        assertEquals("", query.getSqlFilter());
    }

    @Test
    @DisplayName("无角色：兜底 SELF，按 userAlias 拼 id = 当前用户")
    void noRoleDefaultsSelf() throws Exception {
        loginAs(4L, 100L, false);
        when(roleService.rolesByUserId(4L)).thenReturn(List.of());
        DataScopeQuery query = new DataScopeQuery();
        aspect.before(pointWith(query), dataScope());
        assertEquals(" AND u.id = 4", query.getSqlFilter());
    }

    @Test
    @DisplayName("超级管理员：跳过过滤，保持默认空串")
    void superAdminSkipped() throws Exception {
        loginAs(1L, 100L, true);
        DataScopeQuery query = new DataScopeQuery();
        aspect.before(pointWith(query), dataScope());
        assertEquals("", query.getSqlFilter());
    }
}
