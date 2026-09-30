package com.lrs.buddy.biz.member.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lrs.buddy.biz.member.security.MemberTokens;
import com.lrs.buddy.framework.common.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * C 端会员身份域（注册即登录、验证码、会话族、刷新轮换与复用检测、实名提交）。
 *
 * 四条依赖 DB 约束的实现，不是"应用层自觉"：
 *
 * 1 **验证码一次性消费用 CAS**：`UPDATE ... SET code_state='CONSUMED' WHERE code_hash=? AND code_state='PENDING'`，
 *    影响行数 0 就是已经被人用掉了。用"先查 PENDING 再改"的话，同一个码并发提交两次会都通过。
 * 2 **同一手机号同一用途只允许一条待验证码**：发新码前先把旧码置 EXPIRED，
 *    否则 `uk_msms_active` 会直接拒绝插入（这是"验证码轰炸"与"多码并存任一可过"的 DB 级防线）。
 * 3 **同设备类型互斥、多设备类型并存**：登录前把该 member+device_type 的旧会话族整族 REVOKED，
 *    `uk_msess_active_family` 是这条规则的最终裁判。
 * 4 **refresh 轮换 + 复用检测**：旧行置 ROTATED 且保留 `refresh_hash`（唯一索引还在），
 *    所以"已用过的 refresh 再次出现"一定能在库里查到并判为重放 → **整族撤销**。
 *    只把旧令牌作废而不检测复用，等于被盗后攻击者和失主同时失效、谁都不知道发生过什么。
 */
@Slf4j
@Service
public class MemberAuthService {

    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern ID_NO = Pattern.compile("^\\d{17}[\\dXx]$");
    private static final int CODE_TTL_SECONDS = 300;
    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final List<String> DEVICE_TYPES = List.of("H5", "APP", "MINI", "VEHICLE", "OPS");

    private final JdbcTemplate jdbc;
    private final MemberTokens tokens;
    private final com.lrs.buddy.biz.member.repo.MemberSessionRevoker revoker;
    private final String pepper;
    private final boolean mockRealnameAutoApprove;
    private final boolean mockSmsEchoCode;
    private final long refreshDays;

    public MemberAuthService(JdbcTemplate jdbc, MemberTokens tokens,
                             com.lrs.buddy.biz.member.repo.MemberSessionRevoker revoker,
                             @Value("${buddy.member.phone-pepper:}") String pepper,
                             @Value("${buddy.member.mock-realname-auto-approve:false}") boolean mockRealnameAutoApprove,
                             @Value("${buddy.member.mock-sms-echo-code:false}") boolean mockSmsEchoCode,
                             @Value("${buddy.member.refresh-token-days:30}") long refreshDays) {
        if (pepper == null || pepper.trim().length() < 32) {
            throw new IllegalStateException("buddy.member.phone-pepper 未配置或长度不足 32 字节："
                    + "手机号哈希的 pepper 是「库里有摘要也无法反查号码」的前提");
        }
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.revoker = revoker;
        this.pepper = pepper;
        this.mockRealnameAutoApprove = mockRealnameAutoApprove;
        this.mockSmsEchoCode = mockSmsEchoCode;
        this.refreshDays = refreshDays;
    }

    /** 发码结果：只有 MOCK 通道才回显验证码，且必须开关控制（接真实通道后还回显等于短信形同作废）。 */
    public record CodeTicket(String purpose, Integer expiresInSeconds, String echoCode) {
    }

    public record TokenPair(String accessToken, String refreshToken, long accessExpiresIn, String sessionFamily,
                            String deviceType, Long memberId) {
    }

    public record MemberView(Long memberId, String memberNo, String nickname, String maskPhone,
                             String realnameState, String memberState) {
    }

    // ---------------- 验证码 ----------------

    @Transactional
    public CodeTicket sendCode(String phone, String purpose) {
        requirePhone(phone);
        String normalized = normalizePurpose(purpose);
        LocalDateTime now = LocalDateTime.now();
        // 先让同用途的旧码失效，否则撞 uk_msms_active（同一手机号同一用途只允许一条待验证）
        jdbc.update("UPDATE member_sms_code SET code_state = 'EXPIRED', update_time = ? WHERE phone_hash = ? "
                + "AND purpose = ? AND code_state = 'PENDING'", Timestamp.valueOf(now), phoneHash(phone), normalized);
        String code = randomCode();
        try {
            // active_key 是生成列（CASE WHEN code_state='PENDING' THEN phone_hash#purpose），
            // 不得也不需写入：插值会被 DB 拒，而它的存在就是“一手机号一用途只一条待验码”的实现
            jdbc.update("""
                    INSERT INTO member_sms_code (id, phone_hash, purpose, code_hash, code_state, attempts,
                            max_attempts, sent_at, expires_at, create_time, update_time, version)
                    VALUES (?,?,?,?, 'PENDING', 0, ?, ?, ?, ?, ?, 0)
                    """, IdWorker.getId(), phoneHash(phone), normalized, CryptoUtil.hmacSha256Hex(pepper, code),
                    MAX_CODE_ATTEMPTS, Timestamp.valueOf(now), Timestamp.valueOf(now.plusSeconds(CODE_TTL_SECONDS)),
                    Timestamp.valueOf(now), Timestamp.valueOf(now));
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("验证码发送过于频繁，请稍后再试", e);
        }
        log.info("验证码已下发：phone={}, purpose={}, channel=MOCK", mask(phone), normalized);
        return new CodeTicket(normalized, CODE_TTL_SECONDS, mockSmsEchoCode ? code : null);
    }

    /**
     * 消费验证码：CAS 保证一次性。
     *
     * @return true 表示本次调用真正消费掉了这条码（并发下只有一个线程能拿到 true）
     */
    private boolean consumeCode(String phone, String purpose, String code) {
        String phoneHash = phoneHash(phone);
        String key = phoneHash + "#" + purpose;
        Map<String, Object> row = firstMap("SELECT id, attempts, code_state, expires_at FROM member_sms_code "
                + "WHERE active_key = ? ORDER BY sent_at DESC", key);
        if (row == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean fresh = row.get("expires_at") != null
                && ((Timestamp) row.get("expires_at")).toLocalDateTime().isAfter(now);
        if (!fresh) {
            jdbc.update("UPDATE member_sms_code SET code_state = 'EXPIRED', update_time = ? WHERE id = ?",
                    Timestamp.valueOf(now), row.get("id"));
            return false;
        }
        boolean matched = CryptoUtil.hmacSha256Hex(pepper, code).equals(
                jdbc.queryForObject("SELECT code_hash FROM member_sms_code WHERE id = ?", String.class, row.get("id")));
        if (!matched) {
            int attempts = ((Number) row.get("attempts")).intValue() + 1;
            // 次数上限落库而不是内存计数：重启不清零，也不给按设备指纹绕过的空间
            jdbc.update("UPDATE member_sms_code SET attempts = ?, code_state = ?, update_time = ? WHERE id = ? "
                            + "AND code_state = 'PENDING'", attempts,
                    attempts >= MAX_CODE_ATTEMPTS ? "FAILED" : "PENDING", Timestamp.valueOf(now), row.get("id"));
            return false;
        }
        int consumed = jdbc.update("UPDATE member_sms_code SET code_state = 'CONSUMED', consumed_at = ?, "
                + "update_time = ? WHERE id = ? AND code_state = 'PENDING'",
                Timestamp.valueOf(now), Timestamp.valueOf(now), row.get("id"));
        return consumed == 1;
    }

    // ---------------- 登录（注册即登录） ----------------

    @Transactional
    public TokenPair login(String phone, String code, String deviceType, String deviceFingerprint, String ip) {
        requirePhone(phone);
        String device = normalizeDevice(deviceType);
        LocalDateTime now = LocalDateTime.now();
        if (!consumeCode(phone, "LOGIN", code)) {
            throw new IllegalStateException("验证码错误或已失效");
        }
        Long memberId = upsertMember(phone, device, now);

        // 同设备类型互斥：先把旧族整族撤销，再插新行；不先撤销就会撞 uk_msess_active_family
        revoker.revokeSameDeviceType(memberId, device, "NEW_LOGIN_SAME_DEVICE");

        String family = com.lrs.buddy.framework.common.util.Ulids.next();
        return openSession(memberId, family, device, deviceFingerprint, ip, now);
    }

    private Long upsertMember(String phone, String device, LocalDateTime now) {
        String phoneHash = phoneHash(phone);
        List<Long> existing = jdbc.queryForList("SELECT id FROM member_user WHERE phone_hash = ? AND del_flag = 0",
                Long.class, phoneHash);
        if (!existing.isEmpty()) {
            Long id = existing.get(0);
            jdbc.update("UPDATE member_user SET last_login_at = ?, update_time = ? WHERE id = ?",
                    Timestamp.valueOf(now), Timestamp.valueOf(now), id);
            return id;
        }
        long id = IdWorker.getId();
        // 新会员先建会话族以外键式约束通过（member_no 唯一），实名状态保持 NONE：换电 guard 会拒绝
        jdbc.update("""
                INSERT INTO member_user (id, member_no, phone_cipher, phone_hash, nickname, realname_state,
                        member_state, risk_flag, credit_level, register_source, last_login_at, create_time, update_time,
                        version, del_flag, tenant_id)
                VALUES (?,?,?,?,?, 'NONE', 'NORMAL', 0, 0, ?, ?, ?, ?, 0, 0, 1)
                """, id, "M" + id, CryptoUtil.aesGcmEncrypt(cipherKey(), phone), phoneHash,
                "骑手" + phone.substring(7),
                device, Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now));
        jdbc.update("INSERT INTO swap_right_account (id, member_id, plan_id, times_total, times_used, times_occupied, "
                + "valid_from, valid_until, freeze_state, create_time, update_time, version, del_flag, tenant_id) "
                + "VALUES (?, ?, NULL, 0, 0, 0, NULL, NULL, 'NORMAL', ?, ?, 0, 0, 1)",
                id * 10, id, Timestamp.valueOf(now), Timestamp.valueOf(now));
        return id;
    }

    private TokenPair openSession(long memberId, String family, String deviceType, String deviceFingerprint,
                                  String ip, LocalDateTime now) {
        String refreshToken = MemberTokens.newRefreshToken();
        String jti = MemberTokens.newJti();
        Timestamp accessExpires = Timestamp.valueOf(now.plusSeconds(tokens.accessSeconds()));
        Timestamp refreshExpires = Timestamp.valueOf(now.plusDays(refreshDays));
        try {
            // active_family 同样是生成列（ACTIVE 时为 member_id#device_type），不写入
            jdbc.update("""
                    INSERT INTO member_session (id, member_id, session_family, device_type, device_fp_hash, access_jti,
                            refresh_hash, rotated_from_hash, sess_state, issued_at, access_expires_at,
                            refresh_expires_at, last_seen_at, last_ip, create_time, update_time, version, del_flag,
                            tenant_id)
                    VALUES (?,?,?,?,?,?,?,?, 'ACTIVE', ?,?,?, ?,?, ?,?, 0, 0, 1)
                    """, IdWorker.getId(), memberId, family, deviceType, hash(deviceFingerprint), jti,
                    MemberTokens.digest(refreshToken), null, Timestamp.valueOf(now), accessExpires, refreshExpires,
                    Timestamp.valueOf(now), ip, Timestamp.valueOf(now), Timestamp.valueOf(now));
        } catch (DuplicateKeyException e) {
            // 并发同设备类型登录：两路都想建 ACTIVE 族，唯一索引判一个赢。这里不重试成"两个都成功"
            throw new IllegalStateException("同一设备已有新登录发生，请重新登录", e);
        }
        return new TokenPair(tokens.createAccessToken(memberId, family, jti, 1L), refreshToken,
                tokens.accessSeconds(), family, deviceType, memberId);
    }

    // ---------------- 刷新与登出 ----------------

    @Transactional
    public TokenPair refresh(String refreshToken, String deviceType) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalStateException("缺少刷新令牌");
        }
        String hash = MemberTokens.digest(refreshToken);
        Map<String, Object> row = firstMap("SELECT id, member_id, session_family, device_type, device_fp_hash, "
                + "sess_state, refresh_expires_at, last_ip, tenant_id FROM member_session WHERE refresh_hash = ?", hash);
        if (row == null) {
            throw new IllegalStateException("刷新令牌无效");
        }
        String state = String.valueOf(row.get("sess_state"));
        if ("ROTATED".equals(state)) {
            // 复用检测：这张码已经被轮换掉了，现在又出现一次 —— 几乎只能是被复制了
            // 必须走 REQUIRES_NEW 的 revoker：下一行要抛异常，同事务的撤销会被回滚成“什么都没发生”
            revoker.revokeFamily(memberIdOf(row), family(row), "REFRESH_REUSE");
            throw new IllegalStateException("检测到刷新令牌重复使用，该登录已全部失效");
        }
        if (!"ACTIVE".equals(state)) {
            throw new IllegalStateException("会话已失效，请重新登录");
        }
        Timestamp refreshExpires = (Timestamp) row.get("refresh_expires_at");
        LocalDateTime now = LocalDateTime.now();
        if (refreshExpires != null && refreshExpires.toLocalDateTime().isBefore(now)) {
            jdbc.update("UPDATE member_session SET sess_state = 'EXPIRED', update_time = ? WHERE id = ?",
                    Timestamp.valueOf(now), row.get("id"));
            throw new IllegalStateException("登录已过期，请重新登录");
        }
        long memberId = ((Number) row.get("member_id")).longValue();
        String family = String.valueOf(row.get("session_family"));
        int rotated = jdbc.update("UPDATE member_session SET sess_state = 'ROTATED', update_time = ? WHERE id = ? "
                + "AND sess_state = 'ACTIVE'", Timestamp.valueOf(now), row.get("id"));
        if (rotated != 1) {
            throw new IllegalStateException("会话状态已变化，请重新登录");
        }
        String newRefresh = MemberTokens.newRefreshToken();
        String jti = MemberTokens.newJti();
        // 就地轮换：旧行已置 ROTATED（保留旧 refresh_hash 作为复用证据），
        // 新行接同一家族、带 rotated_from_hash 形可追溯链。
        // 不能再走 openSession 插“另一族”也不能先插一条非 ACTIVE 审计行：
        // 那样发给客户端的 refresh 会指向一条永远刷不动的记录。
        Timestamp accessExpires = Timestamp.valueOf(now.plusSeconds(tokens.accessSeconds()));
        Timestamp refreshExpiresAt = (Timestamp) refreshExpires;
        jdbc.update("""
                INSERT INTO member_session (id, member_id, session_family, device_type, device_fp_hash, access_jti,
                        refresh_hash, rotated_from_hash, sess_state, issued_at, access_expires_at, refresh_expires_at,
                        last_seen_at, last_ip, create_time, update_time, version, del_flag, tenant_id)
                VALUES (?,?,?,?,?,?,?,?,'ACTIVE', ?,?,?, ?,?, ?,?, 0, 0, 1)
                """, IdWorker.getId(), memberId, family, row.get("device_type"), row.get("device_fp_hash"), jti,
                MemberTokens.digest(newRefresh), hash, Timestamp.valueOf(now), accessExpires, refreshExpiresAt,
                Timestamp.valueOf(now), row.get("last_ip"), Timestamp.valueOf(now), Timestamp.valueOf(now));
        return new TokenPair(tokens.createAccessToken(memberId, family, jti, 1L), newRefresh,
                tokens.accessSeconds(), family, String.valueOf(row.get("device_type")), memberId);
    }

    @Transactional
    public void logout(long memberId, String sessionFamily) {
        revoker.revokeFamily(memberId, sessionFamily, "USER_LOGOUT");
    }

    private static long memberIdOf(Map<String, Object> row) {
        return ((Number) row.get("member_id")).longValue();
    }

    private static String family(Map<String, Object> row) {
        return String.valueOf(row.get("session_family"));
    }

    // ---------------- 实名 ----------------

    @Transactional
    public String submitRealname(long memberId, String realName, String idNo, String smsCode) {
        Map<String, Object> member = firstMap("SELECT phone_cipher, realname_state, member_state FROM member_user "
                + "WHERE id = ? AND del_flag = 0", memberId);
        if (member == null) {
            throw new IllegalArgumentException("会员不存在：" + memberId);
        }
        if (!"NORMAL".equals(String.valueOf(member.get("member_state")))) {
            throw new IllegalStateException("账号状态不允许提交实名");
        }
        String phone = CryptoUtil.aesGcmDecrypt(cipherKey(), String.valueOf(member.get("phone_cipher")));
        if (!consumeCode(phone, "REALNAME", smsCode)) {
            throw new IllegalStateException("验证码错误或已失效");
        }
        if (realName == null || realName.trim().length() < 2) {
            throw new IllegalArgumentException("姓名不合法");
        }
        if (idNo == null || !ID_NO.matcher(idNo).matches()) {
            throw new IllegalArgumentException("身份证号格式不合法");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean approved = mockRealnameAutoApprove;
        try {
            jdbc.update("""
                    INSERT INTO member_realname (id, member_id, real_name_cipher, id_no_cipher, id_no_hash, mask_name,
                            mask_id_no, review_state, channel, submitted_at, reviewed_at, reject_reason, create_time,
                            update_time, version, del_flag, tenant_id)
                    VALUES (?,?,?,?,?,?,?, ?, 'MOCK', ?, ?, NULL, ?, ?, 0, 0, 1)
                    """, IdWorker.getId(), memberId, CryptoUtil.aesGcmEncrypt(cipherKey(), realName.trim()),
                    CryptoUtil.aesGcmEncrypt(cipherKey(), idNo), hash(idNo), maskName(realName), maskIdNo(idNo),
                    approved ? "VERIFIED" : "SUBMITTED",
                    Timestamp.valueOf(now), approved ? Timestamp.valueOf(now) : null,
                    Timestamp.valueOf(now), Timestamp.valueOf(now));
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("已有实名申请在处理中", e);
        }
        jdbc.update("""
                UPDATE member_user SET realname_state = ?, idname_cipher = ?, idcard_cipher = ?, idcard_hash = ?,
                       update_time = ? WHERE id = ?
                """, approved ? "VERIFIED" : "PENDING", CryptoUtil.aesGcmEncrypt(cipherKey(), realName.trim()),
                CryptoUtil.aesGcmEncrypt(cipherKey(), idNo), hash(idNo), Timestamp.valueOf(now), memberId);
        log.info("实名已提交：member={}, approvedByMock={}", memberId, approved);
        return approved ? "VERIFIED" : "PENDING";
    }

    public MemberView me(long memberId) {
        Map<String, Object> row = firstMap("SELECT id, member_no, nickname, phone_cipher, realname_state, member_state "
                + "FROM member_user WHERE id = ? AND del_flag = 0", memberId);
        if (row == null) {
            throw new IllegalArgumentException("会员不存在：" + memberId);
        }
        return new MemberView(((Number) row.get("id")).longValue(), String.valueOf(row.get("member_no")),
                (String) row.get("nickname"), mask(CryptoUtil.aesGcmDecrypt(cipherKey(),
                        String.valueOf(row.get("phone_cipher")))),
                String.valueOf(row.get("realname_state")), String.valueOf(row.get("member_state")));
    }

    /** 注销前置检查用到的会话统计：有在途单/有权余额的判断在订单与权益侧，这里只给会话事实。 */
    public int activeSessions(long memberId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM member_session WHERE member_id = ? "
                + "AND sess_state = 'ACTIVE'", Integer.class, memberId);
        return count == null ? 0 : count;
    }

    // ---------------- 工具 ----------------

    private String phoneHash(String phone) {
        return CryptoUtil.hmacSha256Hex(pepper, phone);
    }

    /**
     * 加密密钥由 pepper 派生，而不是直接把 pepper 当 AES 密钥用。
     *
     * 同一个秘密同时做 HMAC 密钥和对称加密密钥，是“一个泄露、两个能力同时失”的写法；
     * 派生后两者彼此独立，也与 member 令牌密钥无关。
     */
    private String cipherKey() {
        return CryptoUtil.hmacSha256Hex(pepper, "member-field-encryption");
    }

    private static String hash(String value) {
        return value == null || value.isBlank() ? null : MemberTokens.digest(value);
    }

    private Map<String, Object> firstMap(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void requirePhone(String phone) {
        if (phone == null || !PHONE.matcher(phone).matches()) {
            throw new IllegalArgumentException("手机号格式不合法");
        }
    }

    private static String normalizePurpose(String purpose) {
        String upper = purpose == null ? "LOGIN" : purpose.toUpperCase(java.util.Locale.ROOT);
        return switch (upper) {
            case "LOGIN", "REALNAME", "CANCEL", "REBIND", "BIND" -> upper;
            default -> throw new IllegalArgumentException("未知的验证码用途：" + purpose);
        };
    }

    private static String normalizeDevice(String deviceType) {
        String upper = deviceType == null ? "H5" : deviceType.toUpperCase(java.util.Locale.ROOT);
        if (!DEVICE_TYPES.contains(upper)) {
            throw new IllegalArgumentException("未知的设备类型：" + deviceType);
        }
        return upper;
    }

    private static String randomCode() {
        return String.valueOf(100_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(900_000));
    }

    private static String mask(String phone) {
        return phone == null || phone.length() < 11 ? "***" : phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static String maskName(String name) {
        return name.trim().substring(0, 1) + "*".repeat(Math.max(1, name.trim().length() - 1));
    }

    private static String maskIdNo(String idNo) {
        return idNo.substring(0, 4) + "**********" + idNo.substring(idNo.length() - 4);
    }
}
