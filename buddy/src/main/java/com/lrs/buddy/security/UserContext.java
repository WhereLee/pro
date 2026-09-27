package com.lrs.buddy.security;

/**
 * 当前登录用户上下文。
 *
 * <p>为什么需要它：MyBatis-Plus 的自动填充（createBy/updateBy）发生在 DAO 层，
 * 拿不到 HttpServletRequest；而 SecurityContextHolder 里存的是认证对象，
 * 解析成本较高。这里用一个极薄的 ThreadLocal 做中转。
 *
 * <p>使用约束：
 * <ul>
 *   <li>必须在请求结束时 {@link #clear()}，否则线程池复用会串号——
 *       由 {@code JwtAuthenticationFilter} 的 finally 块保证</li>
 *   <li>异步线程读不到（ThreadLocal 不跨线程）。异步任务需要用户信息时，
 *       请在提交前取出并作为方法参数显式传递，不要指望这里能读到</li>
 * </ul>
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId, String username) {
        USER_ID.set(userId);
        USERNAME.set(username);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static String getUsername() {
        return USERNAME.get();
    }

    public static void clear() {
        USER_ID.remove();
        USERNAME.remove();
    }
}
