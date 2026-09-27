package com.lrs.buddy.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限过滤。
 *
 * <p>与 {@code @PreAuthorize} 的区别：
 * 后者控制"能不能调这个接口"（功能权限），
 * 前者控制"能看到哪些数据"（数据权限）。
 * 只看功能权限是不够的——同样是"查询用户列表"，
 * 集团管理员应该看到全部人，部门主管只应看到本部门的人。
 *
 * <p>用法：注解在 Service 方法上，方法的查询参数需继承
 * {@code DataScopeQuery}，切面会把 SQL 片段写入它的 sqlFilter 字段，
 * 由 Mapper XML 用 {@code ${sqlFilter}} 拼接。
 *
 * <pre>{@code
 * @DataScope(deptAlias = "d", userAlias = "u")
 * public PageResult<SysUserVO> pageUsers(UserQuery query) { ... }
 * }</pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScope {

    /** 部门表在 SQL 中的别名 */
    String deptAlias() default "";

    /** 用户表在 SQL 中的别名 */
    String userAlias() default "";
}
