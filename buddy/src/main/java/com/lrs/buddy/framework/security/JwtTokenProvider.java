package com.lrs.buddy.framework.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析。
 *
 * <p>关于"无状态"的取舍：JWT 的最大优势是服务端不存会话，天然支持水平扩展；
 * 代价是令牌一旦签发，在过期前无法主动失效。
 * 本框架通过 {@code jti}（JWT ID）+ Redis 记录的方式补上这个能力：
 * 需要"强制下线"时，删除 Redis 中的 jti 记录即可让令牌立即失效。
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_SUPER_ADMIN = "superAdmin";
    /** 部门 ID：数据权限过滤需要，放进令牌可避免每次请求回查数据库 */
    public static final String CLAIM_DEPT_ID = "deptId";
    /** 租户 ID：多租户数据隔离需要，随令牌携带，鉴权过滤器据此填充 TenantContext */
    public static final String CLAIM_TENANT_ID = "tenantId";

    private final SecretKey secretKey;
    private final long expireSeconds;

    public JwtTokenProvider(@Value("${buddy.jwt.secret}") String secret,
                            @Value("${buddy.jwt.expire-seconds}") long expireSeconds) {
        // HS256 要求密钥长度不少于 256 bit（32 字节），配置时务必注意
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireSeconds;
    }

    /**
     * 签发令牌。
     *
     * @param loginUser 登录主体
     * @return token 字符串与 jti 的配对；jti 需由调用方写入 Redis
     */
    public String createToken(LoginUser loginUser) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + expireSeconds * 1000);

        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .id(loginUser.getTokenId())
                .subject(loginUser.getUsername())
                .claim(CLAIM_USER_ID, loginUser.getUserId())
                .claim(CLAIM_SUPER_ADMIN, Boolean.TRUE.equals(loginUser.getSuperAdmin()))
                .issuedAt(now)
                .expiration(expire);

        // 部门可能为空（未分配部门的用户），此时不写入该声明，
        // 避免令牌里出现无意义的 null 值
        if (loginUser.getDeptId() != null) {
            builder.claim(CLAIM_DEPT_ID, loginUser.getDeptId());
        }
        // 租户同理：单租户或未分配时可为空，鉴权侧回退到默认租户
        if (loginUser.getTenantId() != null) {
            builder.claim(CLAIM_TENANT_ID, loginUser.getTenantId());
        }

        return builder.signWith(secretKey).compact();
    }

    /**
     * 解析令牌。
     *
     * @throws JwtException 令牌过期、签名错误或格式非法时抛出
     */
    public Claims parseToken(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String generateTokenId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }
}
