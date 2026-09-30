package com.lrs.buddy.biz.member.repo;

import com.lrs.buddy.biz.member.security.MemberJwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 会话族有效性查询（C 端过滤器的强制下线依据）。
 *
 * 三个条件一起判，缺一个都会留下真实的绕过面：
 * <ul>
 *   <li>{@code sess_state='ACTIVE'}：新登录顶号、复用检测、主动登出都会把族置成非 ACTIVE</li>
 *   <li>{@code access_jti} 精确匹配：刷新轮换后旧 access 立即失效，而不是"等到自然过期"</li>
 *   <li>{@code access_expires_at}：库里过期时间到了就拒，避免时钟差导致"库里过期、令牌还能用"</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class MemberSessionGuardImpl implements MemberJwtAuthenticationFilter.MemberSessionGuard {

    private final JdbcTemplate jdbc;

    @Override
    public boolean isActive(Long memberId, String sessionFamily, String jti) {
        if (memberId == null || sessionFamily == null || jti == null) {
            return false;
        }
        Integer alive = jdbc.queryForObject("""
                SELECT COUNT(*) FROM member_session
                WHERE member_id = ? AND session_family = ? AND access_jti = ?
                  AND sess_state = 'ACTIVE' AND access_expires_at > CURRENT_TIMESTAMP
                """, Integer.class, memberId, sessionFamily, jti);
        return alive != null && alive == 1;
    }
}
