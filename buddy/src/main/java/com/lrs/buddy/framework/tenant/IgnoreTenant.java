package com.lrs.buddy.framework.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在方法或类上：其内的数据库操作跳过租户过滤（跨租户可见）。
 *
 * <p>典型用途：登录时按用户名跨租户查找账号、系统级调度/统计任务、平台超管的全局查询。
 * 仅在 {@code buddy.tenant.enabled=true} 时有实际意义；关闭时为无害空操作。
 *
 * @see TenantIgnoreAspect
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface IgnoreTenant {
}
