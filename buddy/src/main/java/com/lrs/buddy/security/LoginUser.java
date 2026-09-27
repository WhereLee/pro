package com.lrs.buddy.security;

import lombok.Builder;
import lombok.Data;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 登录用户主体，即 Spring Security 上下文中的 principal。
 *
 * <p>实现 {@link UserDetails} 以便接入 Security 的标准认证流程。
 * 与常见写法不同的是这里额外保存了 {@code tokenId}（JWT 的 jti），
 * 它是"强制下线"能力的关键——服务端凭 jti 判断一个已签发的令牌是否仍然有效。
 */
@Data
@Builder
public class LoginUser implements UserDetails {

    private Long userId;
    private String username;
    private String password;
    private String nickname;

    /** 所属部门，数据权限过滤需要 */
    private Long deptId;

    /** 权限标识集合，如 sys:user:list */
    private Set<String> permissions;

    /** 是否超级管理员：拥有全部权限，不走权限校验 */
    private Boolean superAdmin;

    /** JWT 的 jti，唯一标识这一次登录产生的令牌 */
    private String tokenId;

    /**
     * 权限集合。
     *
     * <p>超级管理员额外获得 {@code ROLE_ADMIN}，用于 Actuator 端点这类
     * "按角色而非按细粒度权限"控制的场景（Security 的 hasRole 只认 ROLE_ 前缀）。
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new HashSet<>();
        if (Boolean.TRUE.equals(superAdmin)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        if (permissions != null) {
            permissions.forEach(perm -> authorities.add(new SimpleGrantedAuthority(perm)));
        }
        return authorities;
    }

    @Override
    public String getPassword() {
        return this.password;
    }

    @Override
    public String getUsername() {
        return this.username;
    }

    /**
     * 账号是否未过期。这里不做过期策略，返回 true。
     * 若将来需要"账号有效期"，在此扩展即可。
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
