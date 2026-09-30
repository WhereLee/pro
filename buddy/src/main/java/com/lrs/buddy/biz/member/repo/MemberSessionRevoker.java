package com.lrs.buddy.biz.member.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 会话族撤销（必须独立提交）。
 *
 * 为什么单独一个 bean 而不是 Service 里的私有方法：换电 C 端的"refresh 复用检测"要在
 * **抛出异常之前**把整族撤销。私有方法在同一个事务里，异常一抛就一起回滚了——
 * 于是库里看起来什么都没发生，攻击者复制的令牌和我方正常的令牌同时失效，
 * 谁都不知道发生过什么。用 REQUIRES_NEW 把它挂到独立事务里先提交，
 * 撤销才是真的撤销。
 */
@Service
@RequiredArgsConstructor
public class MemberSessionRevoker {

    private final JdbcTemplate jdbc;

    /**
     * @return 被撤销的会话行数（审计与测试都要能看见"确实撤销了几条"）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(long memberId, String sessionFamily, String reason) {
        if (sessionFamily == null || sessionFamily.isBlank()) {
            return 0;
        }
        return jdbc.update("UPDATE member_session SET sess_state = 'REVOKED', revoked_reason = ?, revoked_by = ?, "
                        + "update_time = ? WHERE member_id = ? AND session_family = ? AND sess_state = 'ACTIVE'",
                reason, memberId, Timestamp.valueOf(LocalDateTime.now()), memberId, sessionFamily);
    }

    /** 顶号用：同设备类型的旧族整族撤销（同事务即可，因为不会跟着异常回滚）。 */
    @Transactional
    public int revokeSameDeviceType(long memberId, String deviceType, String reason) {
        return jdbc.update("UPDATE member_session SET sess_state = 'REVOKED', revoked_reason = ?, revoked_by = ?, "
                        + "update_time = ? WHERE member_id = ? AND device_type = ? AND sess_state = 'ACTIVE'",
                reason, memberId, Timestamp.valueOf(LocalDateTime.now()), memberId, deviceType);
    }

    /** 全部撤销（注销/风控封禁时用）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllOfMember(long memberId, String reason) {
        return jdbc.update("UPDATE member_session SET sess_state = 'REVOKED', revoked_reason = ?, revoked_by = ?, "
                        + "update_time = ? WHERE member_id = ? AND sess_state = 'ACTIVE'",
                reason, memberId, Timestamp.valueOf(LocalDateTime.now()), memberId);
    }
}
