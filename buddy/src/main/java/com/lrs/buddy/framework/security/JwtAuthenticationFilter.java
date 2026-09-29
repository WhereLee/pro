package com.lrs.buddy.framework.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.framework.common.response.ResultCode;
import com.lrs.buddy.framework.modules.sys.service.SysMenuService;
import com.lrs.buddy.framework.modules.sys.service.SysRoleService;
import com.lrs.buddy.framework.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * JWT 鉴权过滤器。
 *
 * <p>职责：从请求头取出令牌 → 校验有效性 → 加载权限 → 放入 SecurityContext。
 * 继承 {@link OncePerRequestFilter} 保证一次请求只执行一次（转发、包含等场景不会被重复调用）。
 *
 * <p>权限加载说明：这里每次请求都会查询用户权限。
 * 对后台管理系统（并发量有限、权限条目少）而言开销可忽略，
 * 换来的是权限变更<b>立即生效</b>——若把权限写进令牌，改了角色还得等令牌过期才生效。
 * 若将来并发量上来，可在此处加 Redis 缓存（注意配套处理"角色变更清缓存"）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenService tokenService;
    private final SysRoleService roleService;
    private final SysMenuService menuService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        try {
            if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                Claims claims = jwtTokenProvider.parseToken(token);
                Long userId = Long.valueOf(String.valueOf(claims.get("userId")));
                String jti = claims.getId();

                // 会话校验：Redis 中记录的 jti 与令牌不一致，说明被强制下线或已在别处登录
                if (!tokenService.isTokenValid(userId, jti)) {
                    writeError(response, ResultCode.FORCED_OFFLINE);
                    return;
                }

                boolean superAdmin = Boolean.TRUE.equals(claims.get("superAdmin", Boolean.class));
                Set<String> permissions = Set.copyOf(menuService.userPerms(userId, superAdmin));

                Long tenantId = readTenantId(claims);
                LoginUser loginUser = LoginUser.builder()
                        .userId(userId)
                        .username(claims.getSubject())
                        .deptId(readDeptId(claims))
                        .tenantId(tenantId)
                        .permissions(permissions)
                        .superAdmin(superAdmin)
                        .tokenId(jti)
                        .build();

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        loginUser, null, loginUser.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // 供 MP 自动填充 createBy/updateBy 使用
                UserContext.set(userId, claims.getSubject());
                // 供 MP 租户拦截器使用：本次请求的数据可见范围限定在该租户（单租户时拦截器未装配、无副作用）
                if (tenantId != null) {
                    TenantContext.setTenantId(tenantId);
                }
                // 刷新在线台账的活跃时间
                tokenService.touch(userId);
            }
        } catch (ExpiredJwtException e) {
            writeError(response, ResultCode.TOKEN_EXPIRED);
            return;
        } catch (JwtException | IllegalArgumentException e) {
            writeError(response, ResultCode.TOKEN_INVALID);
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理：容器线程是复用的，ThreadLocal 不清理会导致用户信息/租户串号
            UserContext.clear();
            TenantContext.clear();
        }
    }

    /** 令牌里可能没有部门声明（用户未分配部门），此时返回 null */
    private Long readDeptId(Claims claims) {
        Object deptId = claims.get(JwtTokenProvider.CLAIM_DEPT_ID);
        if (deptId == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(deptId));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 令牌里可能没有租户声明（单租户/旧令牌），此时返回 null，由拦截器回退到默认租户 */
    private Long readTenantId(Claims claims) {
        Object tenantId = claims.get(JwtTokenProvider.CLAIM_TENANT_ID);
        if (tenantId == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(tenantId));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * 鉴权失败时直接写 JSON 响应。
     *
     * <p>为什么不抛异常交给全局异常处理器：鉴权失败发生在进入 DispatcherServlet
     * 之前，{@code @RestControllerAdvice} 捕获不到，只能在过滤器里就地输出。
     */
    private void writeError(HttpServletResponse response, ResultCode resultCode) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), R.fail(resultCode));
    }
}
