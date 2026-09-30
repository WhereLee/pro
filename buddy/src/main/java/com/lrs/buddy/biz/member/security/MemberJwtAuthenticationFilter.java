package com.lrs.buddy.biz.member.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * C 端令牌解析过滤器。
 *
 * 与后台 {@code JwtAuthenticationFilter} 的关键差别：**这里不做权限查询**。
 * member 域只有"登录/未登录"两种状态，业务权限靠归属校验（这单是不是你的），
 * 不是靠 RBAC 权限码。把 hasAuthority 塞进 C 端只会诱导"用权限码当数据隔离"的错误做法。
 *
 * 任何解析失败都**不设置认证**而不是写错误响应：交给过滤链的 EntryPoint 统一出 401，
 * 这样"过期、签名错、串域、缺头"四种原因对外是同一个不可区分的响应——
 * 不给攻击者区分"令牌是否存在"的机会。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /** C 端主体的固定权限：仅用于"已登录"判定与审计，不参与后台权限体系。 */
    public static final String ROLE_MEMBER = "ROLE_MEMBER";

    private final MemberTokens tokens;
    private final MemberSessionGuard sessionGuard;

    /**
     * 会话族是否仍有效的查询口。
     *
     * 为什么必须在过滤器里查库而不是只验签：不查的话，“复用检测 → 整族撤销”只是改了库，
     * 已发出去的 access 仍然能一路用到自然过期（2 小时），所谓强制下线/全族失效根本不成立。
     * 后台链每请求查权限是同一个取舍：宁可多一次查询，也不让“吊销”停留在纸面。
     */
    public interface MemberSessionGuard {
        boolean isActive(Long memberId, String sessionFamily, String jti);
    }

    /** 登录后的 C 端主体。 */
    public record MemberPrincipal(Long memberId, String sessionFamily, Long tenantId) {

        public List<GrantedAuthority> authorities() {
            return List.of(new SimpleGrantedAuthority(ROLE_MEMBER));
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = tokens.parse(header.substring(BEARER_PREFIX.length()).trim());
                Long memberId = Long.valueOf(String.valueOf(claims.get(MemberTokens.CLAIM_MEMBER_ID)));
                String family = claims.get(MemberTokens.CLAIM_SESSION, String.class);
                if (!sessionGuard.isActive(memberId, family, claims.getId())) {
                    // 族已被撤销（新登录顶号、复用检测、主动登出）：令牌未过期也不认
                    log.debug("C 端会话已失效：member={}, family={}", memberId, family);
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }
                MemberPrincipal principal = new MemberPrincipal(
                        memberId, family, claims.get(MemberTokens.CLAIM_TENANT_ID, Long.class));
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.authorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException e) {
                // AuthenticationException 也是 RuntimeException，接一个就行
                log.debug("C 端令牌校验未通过：{}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    /** 当前登录会员 ID；未登录抛异常而不是返回 null——空值一路往下传迟早变成越权。 */
    public static Long currentMemberId() {
        return (Long) currentPrincipal().memberId();
    }

    public static MemberPrincipal currentPrincipal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof MemberPrincipal principal)) {
            throw new IllegalStateException("当前请求没有 C 端登录态");
        }
        return principal;
    }
}
