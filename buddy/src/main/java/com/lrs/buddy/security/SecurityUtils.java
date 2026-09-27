package com.lrs.buddy.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 获取当前登录用户的快捷入口。
 *
 * <p>封装 SecurityContextHolder 的样板代码，避免业务代码里到处出现
 * {@code (LoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal()}
 * 这种既长又容易漏判空的写法。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * @return 当前登录用户；未登录、匿名或主体类型不匹配时返回 null
     */
    public static LoginUser getLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof LoginUser loginUser ? loginUser : null;
    }

    public static Long getUserId() {
        LoginUser user = getLoginUser();
        return user == null ? null : user.getUserId();
    }

    public static String getUsername() {
        LoginUser user = getLoginUser();
        return user == null ? null : user.getUsername();
    }

    public static boolean isSuperAdmin() {
        LoginUser user = getLoginUser();
        return user != null && Boolean.TRUE.equals(user.getSuperAdmin());
    }
}
