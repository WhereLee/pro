package com.lrs.buddy.biz.swap.intervention;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lrs.buddy.biz.swap.flow.SwapFlowService;
import com.lrs.buddy.biz.swap.order.OrderEvent;
import com.lrs.buddy.biz.swap.order.OrderState;
import com.lrs.buddy.biz.swap.order.SwapOrderFsm;
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
 * 订单人工干预的申请与复核（M2 B3）。
 *
 * 三条设计决定，都不是形式：
 *
 * 1 **申请时就校验状态机合法性**（用 {@code canFire}，不改变状态）。
 *    否则会出现一堆"申请成功、审批通过、执行必然失败"的单子，把复核队列变成噪声；
 *    而真正的失败原因（状态非法）在申请那一刻就已经可知。
 * 2 **审批人与申请人必须不同**，且同时受 DB CHECK 与 CAS 的 {@code approver_id <> applicant_id} 约束。
 *    只在 Java 里判断的话，一次代码回滚就把这条防线删了；库里钉住才叫约束。
 * 3 **审批记录先独立提交，再执行订单动作**（见 {@link SwapInterventionRecorder}）。
 *    执行失败必须留下 FAILED + 原因，而不是"什么都没发生过"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwapInterventionService {

    private final JdbcTemplate jdbc;
    private final SwapOrderRepository repo;
    private final SwapFlowService flow;
    private final SwapInterventionRecorder recorder;

    public record InterventionView(Long id, String orderNo, String action, String applyState, String reason,
                                   Long applicantId, String applicantName, LocalDateTime appliedAt,
                                   Long approverId, String approverName, LocalDateTime approvedAt,
                                   LocalDateTime executedAt, String rejectReason, String execError) {
    }

    /**
     * 提交干预申请。
     *
     * @throws IllegalStateException 当前状态下这个动作根本不可能执行，或该单已有待处理申请
     */
    @Transactional
    public long apply(String orderNo, String action, String reason, long applicantId, String applicantName) {
        SwapOrderRepository.OrderRow order = repo.findByOrderNo(orderNo);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在：" + orderNo);
        }
        if (reason == null || reason.trim().length() < 5) {
            throw new IllegalArgumentException("干预理由必须不少于 5 个字（无理由的干预无法追责）");
        }
        OrderEvent event = OrderEvent.valueOf(action);
        OrderState state = OrderState.valueOf(order.state());
        if (!SwapOrderFsm.machine().canFire(state, event)) {
            throw new IllegalStateException("当前状态 " + state + " 不允许执行 " + action + "，申请没有意义");
        }
        LocalDateTime now = LocalDateTime.now();
        long id = IdWorker.getId();
        try {
            jdbc.update("INSERT INTO swap_intervention (id, order_id, order_no, action, reason, apply_state, "
                            + "applicant_id, applicant_name, applied_at, create_time, update_time, version, del_flag, "
                            + "tenant_id) VALUES (?,?,?,?,?, 'PENDING', ?,?,?,?,?, 0, 0, ?)",
                    id, order.id(), orderNo, action, reason.trim(), applicantId, applicantName,
                    Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now), order.tenantId());
        } catch (DuplicateKeyException e) {
            // uk_iv_active：同一笔单只允许一条待处理申请，否则可能被两个人各自批准并执行两次
            throw new IllegalStateException("该订单已有待处理的干预申请，请先处理它", e);
        }
        log.info("干预申请已提交：id={}, order={}, action={}, applicant={}", id, orderNo, action, applicantName);
        return id;
    }

    /**
     * 复核通过并执行。
     *
     * 返回值是**执行后的订单状态**，不是"审批成功"这种半信息：调用方（后台页面）要能立刻看到
     * 这一单现在到底在哪。
     */
    @Transactional
    public String approveAndExecute(long id, long approverId, String approverName) {
        Map<String, Object> row = loadForUpdate(id);
        if (!"PENDING".equals(String.valueOf(row.get("apply_state")))) {
            throw new IllegalStateException("申请单不是待复核状态，不能审批");
        }
        long applicantId = ((Number) row.get("applicant_id")).longValue();
        if (applicantId == approverId) {
            throw new IllegalStateException("干预复核必须由另一个人执行（申请人不能自批）");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!recorder.markApproved(id, approverId, approverName, now)) {
            throw new IllegalStateException("申请单状态已变化（可能已被他人处理），请刷新后重试");
        }
        String orderNo = String.valueOf(row.get("order_no"));
        String action = String.valueOf(row.get("action"));
        SwapOrderRepository.OrderRow order = repo.findByOrderNo(orderNo);
        if (order == null) {
            recorder.markFailed(id, "订单不存在：" + orderNo, LocalDateTime.now());
            throw new IllegalStateException("订单不存在，干预已标记为失败");
        }
        try {
            execute(action, order.id(), String.valueOf(row.get("reason")));
        } catch (RuntimeException e) {
            recorder.markFailed(id, e.getMessage(), LocalDateTime.now());
            throw e;
        }
        recorder.markExecuted(id, LocalDateTime.now());
        return repo.findByOrderNo(orderNo).state();
    }

    private void execute(String action, long orderId, String reason) {
        switch (action) {
            case "ADMIN_ABORT" -> flow.adminAbort(orderId, reason);
            case "ADMIN_RESOLVE_COMPLETED" -> flow.adminResolveCompleted(orderId, reason);
            case "ADMIN_RESOLVE_ABORTED" -> flow.adminResolveAborted(orderId, reason);
            default -> throw new IllegalStateException("不支持的干预动作：" + action);
        }
    }

    @Transactional
    public void reject(long id, long approverId, String approverName, String reason) {
        Map<String, Object> row = loadForUpdate(id);
        if (!"PENDING".equals(String.valueOf(row.get("apply_state")))) {
            throw new IllegalStateException("申请单不是待复核状态，不能驳回");
        }
        if (((Number) row.get("applicant_id")).longValue() == approverId) {
            throw new IllegalStateException("驳回同样必须由另一个人执行");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw new IllegalArgumentException("驳回理由不能少于 5 个字");
        }
        if (!recorder.markRejected(id, approverId, approverName, reason.trim(), LocalDateTime.now())) {
            throw new IllegalStateException("申请单状态已变化（可能已被他人处理），驳回未生效");
        }
    }

    public List<InterventionView> byStatus(String state, int limit) {
        return jdbc.query("SELECT * FROM swap_intervention WHERE apply_state = ? ORDER BY applied_at DESC LIMIT ?",
                SwapInterventionService::toView, state, Math.max(1, Math.min(limit, 100)));
    }

    public List<InterventionView> ofOrder(String orderNo) {
        return jdbc.query("SELECT * FROM swap_intervention WHERE order_no = ? ORDER BY applied_at DESC",
                SwapInterventionService::toView, orderNo);
    }

    private Map<String, Object> loadForUpdate(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, order_no, action, reason, apply_state, "
                + "applicant_id FROM swap_intervention WHERE id = ? AND del_flag = 0", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("干预申请不存在：" + id);
        }
        return rows.get(0);
    }

    private static InterventionView toView(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Timestamp approved = rs.getTimestamp("approved_at");
        Timestamp applied = rs.getTimestamp("applied_at");
        Timestamp executed = rs.getTimestamp("executed_at");
        return new InterventionView(rs.getLong("id"), rs.getString("order_no"), rs.getString("action"),
                rs.getString("apply_state"), rs.getString("reason"), rs.getLong("applicant_id"),
                rs.getString("applicant_name"), applied == null ? null : applied.toLocalDateTime(),
                rs.getObject("approver_id") == null ? null : rs.getLong("approver_id"),
                rs.getString("approver_name"), approved == null ? null : approved.toLocalDateTime(),
                executed == null ? null : executed.toLocalDateTime(),
                rs.getString("reject_reason"), rs.getString("exec_error"));
    }
}
