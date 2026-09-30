package com.lrs.buddy.biz.swap.intervention;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 干预申请单的状态落库（每步独立提交）。
 *
 * 为什么必须 REQUIRES_NEW：审批通过 → 执行订单动作 之间，执行**很可能抛异常**（状态非法、
 * 设备事实缺失、并发被抢）。若审批记录与执行动作同处一个事务，异常会把"谁批准了"这条记录一起
 * 抹掉——最后只剩下"有人执行了一次失败的干预"，却无法回答"是谁授权的"。
 * 审计链不能依赖被审计动作的成功，这是这类复核表的底线。
 */
@Service
@RequiredArgsConstructor
public class SwapInterventionRecorder {

    private final JdbcTemplate jdbc;

    /**
     * @return true 表示本次调用完成了 PENDING → APPROVED 的迁移（并发下只有一个能拿到 true）
     *
     * WHERE 里不能写 {@code approver_id <> applicant_id}：那一刻 approver_id 还是 NULL，
     * 三值逻辑下条件永远为 UNKNOWN，会导致每一条审批都匹配 0 行（“审批默默无效”）。
     * 双人约束由新值上的表级 CHECK（ck_iv_two_person）与服务层前置校验两道保证，已足够。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markApproved(long id, long approverId, String approverName, LocalDateTime now) {
        return jdbc.update("UPDATE swap_intervention SET apply_state = 'APPROVED', approver_id = ?, approver_name = ?, "
                + "approved_at = ?, update_time = ?, version = version + 1 WHERE id = ? AND apply_state = 'PENDING'",
                approverId, approverName, Timestamp.valueOf(now), Timestamp.valueOf(now), id) == 1;
    }

    /**
     * 驳回同样返回影响行数：处置类写操作不检查行数，就等于允许它默默不发生。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRejected(long id, long approverId, String approverName, String reason, LocalDateTime now) {
        return jdbc.update("UPDATE swap_intervention SET apply_state = 'REJECTED', approver_id = ?, approver_name = ?, "
                + "approved_at = ?, reject_reason = ?, update_time = ?, version = version + 1 "
                + "WHERE id = ? AND apply_state = 'PENDING'",
                approverId, approverName, Timestamp.valueOf(now), reason, Timestamp.valueOf(now), id) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExecuted(long id, LocalDateTime now) {
        jdbc.update("UPDATE swap_intervention SET apply_state = 'EXECUTED', executed_at = ?, update_time = ?, "
                + "version = version + 1 WHERE id = ? AND apply_state = 'APPROVED'",
                Timestamp.valueOf(now), Timestamp.valueOf(now), id);
    }

    /** 执行失败：状态置 FAILED 并把原因写进 exec_error（记录必须留下来，否则失败无痕）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long id, String error, LocalDateTime now) {
        jdbc.update("UPDATE swap_intervention SET apply_state = 'FAILED', exec_error = ?, executed_at = ?, "
                + "update_time = ?, version = version + 1 WHERE id = ? AND apply_state = 'APPROVED'",
                truncate(error), Timestamp.valueOf(now), Timestamp.valueOf(now), id);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 250 ? value : value.substring(0, 250);
    }
}
