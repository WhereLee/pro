package com.lrs.buddy.biz.swap.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 权益发放与调整（后台侧，M4 资金域的最小入口）。
 *
 * 存在的理由很具体：C 端注册即登录的新会员额度是 0（正确的领域行为，未购买不能换电），
 * 但系统里必须有"把额度给到账户"的入口，否则"注册 → 发放 → 换电"这条链只能靠改库闭合。
 *
 * 两条口径写死在这里，不留给调用方猜：
 * <ul>
 *   <li>次数<b>累加</b>，有效期取 {@code max(现有, now+validDays)}：发放不能缩短用户已有的有效期，
 *       否则"补发 1 次"会把一个还剩 20 天的账户续期逻辑一起改掉。</li>
 *   <li>每次发放都落一条 {@code GRANT} 流水，带 {@code balance_after}。
 *       只改账户不记流水，事后就无法回答"这些次数是谁在什么时候给的"。</li>
 * </ul>
 *
 * 幂等边界（不是遗漏，是明确记录）：人工调整没有业务幂等键（没有订单号可挂），
 * 所以本接口**不保证重复提交只生效一次**；真正的幂等要等 M4 的支付驱动发放（挂支付单号）。
 * 这里靠"必填备注 + 返回发放后余额"让误操作可见、可追责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberRightAdminService {

    private final JdbcTemplate jdbc;
    private final SwapOrderRepository repo;

    public record GrantView(long memberId, long accountId, int timesTotal, int timesUsed, int timesOccupied,
                            LocalDateTime validUntil, int grantedTimes) {
    }

    @Transactional
    public GrantView grant(long memberId, int times, int validDays, String remark) {
        if (times < 1) {
            throw new IllegalArgumentException("发放次数必须大于 0");
        }
        if (validDays < 1) {
            throw new IllegalArgumentException("有效期天数必须大于 0");
        }
        if (remark == null || remark.trim().length() < 5) {
            throw new IllegalArgumentException("备注不能少于 5 个字：发放额度是资金动作，必须写明依据");
        }
        Integer memberExists = jdbc.queryForObject("SELECT COUNT(*) FROM member_user WHERE id = ? AND del_flag = 0",
                Integer.class, memberId);
        if (memberExists == null || memberExists == 0) {
            throw new IllegalArgumentException("会员不存在：" + memberId);
        }
        LocalDateTime now = LocalDateTime.now();
        Timestamp until = Timestamp.valueOf(now.plusDays(validDays));
        List<Map<String, Object>> accounts = jdbc.queryForList("SELECT id, times_total FROM swap_right_account "
                + "WHERE member_id = ? AND del_flag = 0", memberId);
        if (accounts.isEmpty()) {
            long accountId = IdWorker.getId();
            try {
                jdbc.update("INSERT INTO swap_right_account (id, member_id, plan_id, times_total, times_used, "
                                + "times_occupied, valid_from, valid_until, freeze_state, auto_renew, create_time, "
                                + "update_time, version, del_flag, tenant_id) "
                                + "VALUES (?, ?, NULL, ?, 0, 0, ?, ?, 'NORMAL', 0, ?, ?, 0, 0, 1)",
                        accountId, memberId, times, Timestamp.valueOf(now), until, Timestamp.valueOf(now),
                        Timestamp.valueOf(now));
            } catch (DuplicateKeyException e) {
                // uk_racc_member：同一会员并发首次发放，只能有一行
                throw new IllegalStateException("该会员的权益账户正在被创建，请稍后重试", e);
            }
            repo.insertRightTransaction(IdWorker.getId(), memberId, accountId, null, "GRANT", times, times, now,
                    remark.trim(), null, 1L);
            return new GrantView(memberId, accountId, times, 0, 0, until.toLocalDateTime(), times);
        }
        long accountId = ((Number) accounts.get(0).get("id")).longValue();
        int currentTotal = ((Number) accounts.get(0).get("times_total")).intValue();
        jdbc.update("UPDATE swap_right_account SET times_total = times_total + ?, valid_from = COALESCE(valid_from, ?), "
                        + "valid_until = CASE WHEN valid_until IS NULL OR valid_until < ? THEN ? ELSE valid_until END, "
                        + "update_time = ?, version = version + 1 WHERE id = ? AND del_flag = 0",
                times, Timestamp.valueOf(now), until, until, Timestamp.valueOf(now), accountId);
        int balanceAfter = currentTotal + times;
        repo.insertRightTransaction(IdWorker.getId(), memberId, accountId, null, "GRANT", times, balanceAfter, now,
                remark.trim(), null, 1L);
        log.info("权益已发放：member={}, +{} 次, 至 {}", memberId, times, until);
        return new GrantView(memberId, accountId, balanceAfter, 0, 0, until.toLocalDateTime(), times);
    }

    /** 权益快照（C 端与后台共用这一个读出口；不含任何凭证类字段）。 */
    public Map<String, Object> accountOf(long memberId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT times_total, times_used, times_occupied, "
                + "valid_from, valid_until, freeze_state FROM swap_right_account WHERE member_id = ? AND del_flag = 0",
                memberId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
