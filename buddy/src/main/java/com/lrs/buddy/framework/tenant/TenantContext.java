package com.lrs.buddy.framework.tenant;

import java.util.function.Supplier;

/**
 * 当前租户上下文（ThreadLocal）。
 *
 * <p>与 {@code UserContext} 同构：一个极薄的线程内中转站，供 MyBatis-Plus 的
 * {@code TenantLineInnerInterceptor} 在 DAO 层读取"当前是哪个租户"，从而自动为
 * SQL 追加 {@code tenant_id = ?} 条件（并为 INSERT 注入 tenant_id 列）。
 *
 * <p>使用约束：
 * <ul>
 *   <li>请求结束必须 {@link #clear()}，否则容器线程复用会串租户——由 JwtAuthenticationFilter 的 finally 保证</li>
 *   <li>ThreadLocal 不跨线程：异步/调度线程需通过 AsyncConfig 的 TaskDecorator 传播，或显式 {@link #runAs}</li>
 *   <li>{@code buddy.tenant.enabled=false}（默认）时不装配租户拦截器，本上下文的值不会被任何 SQL 读取，等价于单租户</li>
 * </ul>
 */
public final class TenantContext {

    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IGNORE = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(Long tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static Long getTenantId() {
        return TENANT_ID.get();
    }

    /** 标记当前线程"忽略租户过滤"，配合 {@code @IgnoreTenant} 或系统级跨租户任务使用。 */
    public static void setIgnore(boolean ignore) {
        IGNORE.set(ignore);
    }

    public static boolean isIgnore() {
        return Boolean.TRUE.equals(IGNORE.get());
    }

    public static void clear() {
        TENANT_ID.remove();
        IGNORE.remove();
    }

    /** 以指定租户身份执行一段逻辑，执行完恢复原租户（支持嵌套）。 */
    public static void runAs(Long tenantId, Runnable action) {
        Long prev = TENANT_ID.get();
        TENANT_ID.set(tenantId);
        try {
            action.run();
        } finally {
            restore(prev);
        }
    }

    /** {@link #runAs} 的有返回值版本。 */
    public static <T> T callAs(Long tenantId, Supplier<T> action) {
        Long prev = TENANT_ID.get();
        TENANT_ID.set(tenantId);
        try {
            return action.get();
        } finally {
            restore(prev);
        }
    }

    /** 在"忽略租户过滤"下执行（跨租户系统操作，如登录时按用户名查找账号、调度心跳）。 */
    public static <T> T callIgnoring(Supplier<T> action) {
        boolean prev = isIgnore();
        IGNORE.set(true);
        try {
            return action.get();
        } finally {
            IGNORE.set(prev);
        }
    }

    private static void restore(Long prev) {
        if (prev == null) {
            TENANT_ID.remove();
        } else {
            TENANT_ID.set(prev);
        }
    }
}
