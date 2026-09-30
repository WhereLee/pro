package com.lrs.buddy.biz.member.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

/**
 * C 端会员令牌（member 域）。
 *
 * 为什么必须独立密钥而不是复用后台的 `buddy.jwt.secret`：
 * 共用一个密钥时，后台签发的令牌和 C 端签发的令牌**在密码学上无法区分**，
 * 只能靠过滤器里的路径判断兜着——一旦某个新接口挂在两条链都能匹配的路径上，
 * 会员令牌就能去访问后台接口（或反向）。独立密钥让"串域"在验签阶段就失败，
 * 这正是本项目立的约束：**串域必须 401，不是 403**。
 *
 * 密钥缺失时**直接启动失败**（不给默认值）：默认值会一路活到生产，
 * 变成"所有人都知道的密钥"，比没有密钥更糟。
 */
@Component
public class MemberTokens {

    public static final String REALM = "realm";
    public static final String REALM_MEMBER = "member";
    public static final String CLAIM_MEMBER_ID = "memberId";
    public static final String CLAIM_SESSION = "sid";
    public static final String CLAIM_TENANT_ID = "tenantId";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey secretKey;
    private final long accessSeconds;

    public MemberTokens(@Value("${buddy.member.jwt-secret:}") String secret,
                        @Value("${buddy.member.access-token-seconds:7200}") long accessSeconds) {
        if (secret == null || secret.trim().length() < 32) {
            throw new IllegalStateException(
                    "buddy.member.jwt-secret 未配置或长度不足 32 字节：C 端令牌与后台令牌必须使用不同密钥，"
                            + "且不允许使用默认值（生产通过环境变量注入）");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessSeconds = accessSeconds;
    }

    /** 签发访问令牌；jti 与 refresh 令牌一样必须与会话行对应，否则无法做复用检测。 */
    public String createAccessToken(long memberId, String sessionFamily, String jti, Long tenantId) {
        Date now = new Date();
        return Jwts.builder()
                .id(jti)
                .subject(String.valueOf(memberId))
                .claim(REALM, REALM_MEMBER)
                .claim(CLAIM_MEMBER_ID, memberId)
                .claim(CLAIM_SESSION, sessionFamily)
                .claim(CLAIM_TENANT_ID, tenantId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessSeconds * 1000L))
                .signWith(secretKey)
                .compact();
    }

    public Claims parse(String token) throws JwtException {
        Claims claims = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
        if (!REALM_MEMBER.equals(claims.get(REALM, String.class))) {
            throw new JwtException("令牌域不匹配：非 member 域令牌不能访问 C 端接口");
        }
        return claims;
    }

    /** refresh 令牌是**不透明随机串**（不是 JWT）：它需要能被一次性作废，且不能自带可验证的有效期。 */
    public static String newRefreshToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String newJti() {
        return newRefreshToken();
    }

    /** 库里只存 SHA-256 十六进制摘要（列宽 CHAR(64)）：令牌泄露不等于会话可被复用。 */
    public static String digest(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public long accessSeconds() {
        return accessSeconds;
    }
}
