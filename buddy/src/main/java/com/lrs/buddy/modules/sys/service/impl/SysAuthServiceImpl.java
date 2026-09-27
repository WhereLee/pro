package com.lrs.buddy.modules.sys.service.impl;

import com.lrs.buddy.common.BusinessException;
import com.lrs.buddy.modules.sys.entity.SysUser;
import com.lrs.buddy.modules.sys.model.form.LoginForm;
import com.lrs.buddy.modules.sys.model.vo.LoginVO;
import com.lrs.buddy.modules.sys.model.vo.SysMenuVO;
import com.lrs.buddy.modules.sys.model.vo.UserInfoVO;
import com.lrs.buddy.modules.sys.service.SysAuthService;
import com.lrs.buddy.modules.sys.service.SysMenuService;
import com.lrs.buddy.modules.sys.service.SysRoleService;
import com.lrs.buddy.modules.sys.service.SysUserService;
import com.lrs.buddy.security.JwtTokenProvider;
import com.lrs.buddy.security.LoginUser;
import com.lrs.buddy.security.SecurityUtils;
import com.lrs.buddy.security.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 认证服务实现。
 *
 * <p>登录没有走 Spring Security 自带的 {@code UsernamePasswordAuthenticationFilter}，
 * 而是在这里手工完成校验并直接签发 JWT。原因是前后端分离场景下需要返回
 * JSON 格式的令牌，用表单过滤器反而要额外定制成功/失败处理器，得不偿失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysAuthServiceImpl implements SysAuthService {

    /** 拥有该角色标识的用户视为超级管理员，不做权限校验 */
    private static final String SUPER_ADMIN_ROLE_KEY = "admin";

    private final SysUserService userService;
    private final SysRoleService roleService;
    private final SysMenuService menuService;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public LoginVO login(LoginForm form, String ip, String userAgent) {
        SysUser user = userService.getByUsername(form.getUsername());
        // 关键点：用户不存在与密码错误返回同一句提示。
        // 若分别提示"用户不存在"/"密码错误"，攻击者可用它枚举出哪些账号真实存在。
        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }
        if (Integer.valueOf(1).equals(user.getStatus())) {
            throw new BusinessException("账号已停用，请联系管理员");
        }
        if (!passwordEncoder.matches(form.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        List<String> roleKeys = roleService.roleKeysByUserId(user.getId());
        boolean superAdmin = roleKeys.contains(SUPER_ADMIN_ROLE_KEY);
        Set<String> permissions = Set.copyOf(menuService.userPerms(user.getId(), superAdmin));

        String tokenId = jwtTokenProvider.generateTokenId();
        LoginUser loginUser = LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .deptId(user.getDeptId())
                .permissions(permissions)
                .superAdmin(superAdmin)
                .tokenId(tokenId)
                .build();

        String token = jwtTokenProvider.createToken(loginUser);
        // 登记会话，使"强制下线"能力生效
        tokenService.register(loginUser, ip, userAgent);

        log.info("用户登录成功，username={}, ip={}", user.getUsername(), ip);
        return LoginVO.builder()
                .token(token)
                .expireSeconds(jwtTokenProvider.getExpireSeconds())
                .build();
    }

    @Override
    public void logout() {
        Long userId = SecurityUtils.getUserId();
        if (userId != null) {
            tokenService.logout(userId);
        }
        SecurityContextHolder.clearContext();
    }

    @Override
    public UserInfoVO currentUser() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (loginUser == null) {
            throw new BusinessException("未登录");
        }
        return UserInfoVO.builder()
                .userId(loginUser.getUserId())
                .username(loginUser.getUsername())
                .nickname(loginUser.getNickname())
                .roles(roleService.roleKeysByUserId(loginUser.getUserId()))
                .permissions(List.copyOf(loginUser.getPermissions()))
                .superAdmin(loginUser.getSuperAdmin())
                .build();
    }

    @Override
    public List<SysMenuVO> currentRoutes() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (loginUser == null) {
            throw new BusinessException("未登录");
        }
        return menuService.userRoutes(loginUser.getUserId(),
                Boolean.TRUE.equals(loginUser.getSuperAdmin()));
    }
}
