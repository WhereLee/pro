package com.lrs.buddy.biz.swap.compensation;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 补偿执行器（M3 主体之一）。
 *
 * 台账里每一条 PENDING/FAILED 都是"某件必须发生的事还没发生"，所以执行器的底线是：
 * <ul>
 *   <li><b>可重试</b>：每个动作都写成幂等的（只动仍处于源状态的行，重复执行不叠加效果）。
 *       不幂等的补偿比重试更危险——退两次权益比不退更糟。</li>
 *   <li><b>失败可见</b>：失败累加 attempts + 写 last_error + 指数退避；重试耗尽时记一条差异台账
 *       并打 ERROR（宁可吵，不能静默）。</li>
 *   <li><b>没有执行者的动作不假装完成</b>：本域未实现的动作（退款/工单/告警升级，属 M4/M5）
 *       一律保持 PENDING 并记一条"无执行者"差异。把它标成 DONE 或 SKIPPED 就是谎报完成，
 *       而那正是本项目所有事故的共同形状。</li>
 * </ul>
 *
 * 每个动作在自己的事务里执行：一项失败不能把同批其他单的补偿一起回滚。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwapCompensationExecutor {

    private static final int MAX_ATTEMPTS = 8;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    /** 本域已实现的动作；其余的保持 PENDING 并显式告警（不假装完成）。 */
    private static final Set<String> IMPLEMENTED = Set.of(
            "RELEASE_RESERVATION", "RELEASE_RIGHT", "DEDUCT_RIGHT", "BATTERY_TO_POOL",
            "BATTERY_PENDING_PICKUP", "UNBIND_USER_BATTERY", "LOCK_SLOT", "LOCK_CABINET", "WRITE_DISCREPANCY");

    /**
     * 供契约测试比对：文档 §7.1 的执行者归属必须与这里逐字一致。
     *
     * 光对枚举与 DDL 只能证明"名字对得上"，证不了"有人执行"——而这个集合才是真相：
     * 不在这里、却被排进台账的动作，会一直 PENDING 到有人补上执行者为止。
     */
    public static Set<String> implementedActions() {
        return IMPLEMENTED;
    }

    private final SwapOrderRepository repo;
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final TransactionTemplate txTemplate;
    private final com.lrs.buddy.biz.swap.flow.SwapFlowService flow;

    private static final List<String> BLOCKING = java.util.Arrays.stream(CompensationAction.values())
            .filter(CompensationAction::blocking).map(Enum::name).toList();

    @Scheduled(fixedDelayString = "${swap.compensation.scan-ms:15000}")
    @SchedulerLock(name = "swap-compensation", lockAtMostFor = "PT2M", lockAtLeastFor = "PT2S")
    public void sweep() {
        runOnce();
    }

    /** 暴露给测试：自己造台账后直接驱动一次，不靠 sleep 等真实调度。 */
    public int runOnce() {
        List<Map<String, Object>> due = repo.dueCompensation(LocalDateTime.now(), 50);
        for (Map<String, Object> row : due) {
            try {
                execute(row);
            } catch (RuntimeException e) {
                // 单项异常不拖垮整批（与超时驱动同一条纪律），但必须留下可归因的日志与计数
                registry.counter("compensation.error").increment();
                log.error("补偿项处理异常：id={}, action={}, err={}",
                        row.get("id"), row.get("action"), e.getMessage(), e);
            }
        }
        return due.size();
    }

    private void execute(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        long orderId = ((Number) row.get("order_id")).longValue();
        String action = String.valueOf(row.get("action"));
        Long targetId = row.get("target_id") == null ? null : ((Number) row.get("target_id")).longValue();
        int attempts = row.get("attempts") == null ? 0 : ((Number) row.get("attempts")).intValue();
        LocalDateTime now = LocalDateTime.now();

        if (!IMPLEMENTED.contains(action)) {
            // 不 DONE、不 SKIPPED：保持 PENDING + 差异 + 长退避，让它一直可见直到 M4/M5 补上执行者
            registry.counter("compensation.no_executor", "action", action).increment();
            repo.markCompensationFailed(id, "NO_EXECUTOR:" + action, now.plusMinutes(30), now);
            repo.insertDiscrepancy(IdWorker.getId(), "NOEXEC:" + id, "FACT_MISSING", orderId, null, null, null,
                    null, "{\"action\":\"" + action + "\"}", "补偿动作在本域无执行者（属 M4/M5），保持待处理", 1L);
            log.error("补偿动作无执行者，保持待处理：id={}, order={}, action={}", id, orderId, action);
            return;
        }

        try {
            txTemplate.executeWithoutResult(status -> apply(action, orderId, targetId, row, now));
            if (repo.markCompensationDone(id, now)) {
                registry.counter("compensation.executed", "action", action).increment();
            }
            // 阻塞项做完就把订单收尾：否则单会永远停在 ABORTING，而"谁负责推它一下"没人认领。
            finishOrderIfReady(orderId);
        } catch (RuntimeException e) {
            Duration backoff = backoff(attempts);
            repo.markCompensationFailed(id, e.getMessage(), now.plus(backoff), now);
            registry.counter("compensation.failed", "action", action).increment();
            if (attempts + 1 >= MAX_ATTEMPTS) {
                repo.insertDiscrepancy(IdWorker.getId(), "EXH:" + id, "FACT_MISSING", orderId, null, null, null,
                        null, "{\"attempts\":" + (attempts + 1) + "}",
                        "补偿重试耗尽：" + action + "，" + e.getMessage(), 1L);
                registry.counter("compensation.exhausted", "action", action).increment();
                log.error("补偿重试耗尽，转人工：id={}, order={}, action={}, err={}", id, orderId, action, e.getMessage());
            } else {
                log.warn("补偿执行失败，{} 后重试：id={}, action={}, err={}", backoff, id, action, e.getMessage());
            }
        }
    }

    /** 本单已无阻塞补偿且正停在 ABORTING → 落终态（I8 的另一半：做完才能进）。 */
    private void finishOrderIfReady(long orderId) {
        SwapOrderRepository.OrderRow order = repo.findOrder(orderId);
        if (order == null || !"ABORTING".equals(order.state())) {
            return;
        }
        if (repo.countOpenCompensation(orderId, BLOCKING) > 0) {
            return;
        }
        try {
            flow.finishAborting(orderId);
            registry.counter("compensation.order_finalized").increment();
        } catch (RuntimeException e) {
            registry.counter("compensation.finalize_failed").increment();
            log.error("补偿完成后落终态失败：order={}, err={}", orderId, e.getMessage());
        }
    }

    /** 指数退避：30s → 1m → 2m …… 上限 30min。 */
    static Duration backoff(int attempts) {
        long seconds = 30L << Math.min(Math.max(attempts, 0), 10);
        Duration d = Duration.ofSeconds(seconds);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    /**
     * 业务侧执行。全部写成"只动源状态行"的形式，因此重复执行不叠加效果。
     * 这里刻意不 import 订单状态机：补偿只处理资产/权益/仓位，状态推进仍由流程服务负责。
     */
    private void apply(String action, long orderId, Long targetId, Map<String, Object> row, LocalDateTime now) {
        SwapOrderRepository.OrderRow order = repo.findOrder(orderId);
        switch (action) {
            case "RELEASE_RESERVATION" -> repo.releaseReservations(orderId, "COMP:" + action, now);
            case "LOCK_SLOT" -> {
                if (targetId == null) {
                    throw new IllegalArgumentException("LOCK_SLOT 需要 target_id");
                }
                jdbc.update("UPDATE swap_slot SET slot_state = 'ISOLATED', fault_code = 'COMP_LOCK_SLOT', "
                        + "update_time = ?, version = version + 1 WHERE id = ? AND slot_state <> 'ISOLATED'",
                        Timestamp.valueOf(now), targetId);
            }
            case "LOCK_CABINET" -> {
                if (targetId == null) {
                    throw new IllegalArgumentException("LOCK_CABINET 需要 target_id");
                }
                jdbc.update("UPDATE swap_cabinet SET cabinet_state = 'SAFETY_LOCKED', locked_reason = 'COMP_LOCK', "
                        + "update_time = ?, version = version + 1 WHERE id = ? AND cabinet_state <> 'SAFETY_LOCKED'",
                        Timestamp.valueOf(now), targetId);
            }
            case "BATTERY_TO_POOL" -> {
                if (targetId == null) {
                    throw new IllegalArgumentException("BATTERY_TO_POOL 需要 target_id");
                }
                jdbc.update("UPDATE swap_battery SET battery_state = 'IN_CABINET_CHARGING', holder_user_id = NULL, "
                                + "isolated_reason = NULL, update_time = ?, version = version + 1 "
                                + "WHERE id = ? AND battery_state <> 'IN_CABINET_CHARGING'",
                        Timestamp.valueOf(now), targetId);
            }
            case "BATTERY_PENDING_PICKUP" -> {
                if (targetId == null) {
                    throw new IllegalArgumentException("BATTERY_PENDING_PICKUP 需要 target_id");
                }
                repo.markBatteryPendingPickup(targetId, "COMPENSATION", now);
            }
            case "UNBIND_USER_BATTERY" -> {
                if (targetId == null) {
                    throw new IllegalArgumentException("UNBIND_USER_BATTERY 需要 target_id（电池）");
                }
                // 只关生效绑定；已 HIST 的行不再动 → 重复执行不会多关一次
                jdbc.update("UPDATE swap_battery_binding SET bind_state = 'HIST', end_at = ?, end_reason = "
                                + "'COMP_UNBIND', update_time = ?, version = version + 1 WHERE battery_id = ? "
                                + "AND bind_state = 'ACTIVE'",
                        Timestamp.valueOf(now), Timestamp.valueOf(now), targetId);
            }
            case "RELEASE_RIGHT" -> releaseRight(order, now, "COMP_RELEASE");
            case "DEDUCT_RIGHT" -> deductRight(order, now);
            case "WRITE_DISCREPANCY" -> writeDiscrepancy(orderId, targetId, row, order, now);
            default -> throw new IllegalStateException("未预期的补偿动作：" + action);
        }
    }

    /**
     * 记一条账实差异。
     *
     * 这里原来是一个只打计数器的 noop —— 而 noop 会被标成 DONE，等于"账面说差异已记，实际一行都没有"。
     * 差异类型由排账的人在 remark 里写明（约定 {@code <KIND>:<说明>}），执行器不替它猜一个默认值：
     * 猜出来的 kind 事后无法回答"这条差异是谁判定的"。
     */
    private void writeDiscrepancy(long orderId, Long targetId, Map<String, Object> row, SwapOrderRepository.OrderRow order,
                                  LocalDateTime now) {
        String remark = row.get("remark") == null ? null : String.valueOf(row.get("remark"));
        // 先归一化再按下标切：上一版拿 trim() 后的长度去比未 trim 的下标，
        // 前导空格会把判定推歪（合法输入被拒）。两者必须在同一个串上算。
        String spec = remark == null ? null : remark.trim();
        int sep = spec == null ? -1 : spec.indexOf(':');
        if (sep <= 0 || sep >= spec.length() - 1) {
            throw new IllegalArgumentException("WRITE_DISCREPANCY 必须在 remark 写明差异类型与说明（<KIND>:<说明>）");
        }
        String kind = spec.substring(0, sep).trim().toUpperCase(Locale.ROOT);
        String detail = spec.substring(sep + 1).trim();
        if (kind.isEmpty() || detail.isEmpty()) {
            // kind 为空时 DB 的 CHECK 会拒成 FAILED 反复重试，那不是报错而是惩罚：在入口就拒掉
            throw new IllegalArgumentException("WRITE_DISCREPANCY 的 remark 形如 <KIND>:<说明>，两侧都不得为空");
        }
        String targetType = row.get("target_type") == null ? "ORDER" : String.valueOf(row.get("target_type"));
        Long battery = "BATTERY".equals(targetType) ? targetId : null;
        Long cabinet = "CABINET".equals(targetType) ? targetId : null;
        Long user = "USER".equals(targetType) ? targetId : null;
        // kind 不在这儿做白名单：swap_discrepancy.kind 有 CHECK，写错直接被 DB 拒成 FAILED 重试，
        // 比在 Java 里再维护一份枚举集合更可靠（同一份真相不写两遍）。
        if (!repo.insertDiscrepancy(IdWorker.getId(), "COMP:" + row.get("id"), kind, orderId, cabinet, battery, user,
                null, detail, detail, order == null ? 1L : order.tenantId())) {
            // 去重键已存在 = 这条差异早先已记下（重试路径），幂等视为完成
            registry.counter("compensation.discrepancy_dedup").increment();
        }
    }

    private void releaseRight(SwapOrderRepository.OrderRow order, LocalDateTime now, String reason) {
        if (order == null || !"OCCUPIED".equals(order.rightState())) {
            return;  // 已经退过：幂等直接返回
        }
        SwapOrderRepository.AccountRow account = repo.findAccount(order.userId());
        if (account == null || !repo.releaseRightOccupation(account.id())) {
            throw new IllegalStateException("权益预占释放失败（无预占可退或账户不存在）：" + order.orderNo());
        }
        repo.insertRightTransaction(IdWorker.getId(), order.userId(), account.id(), order.id(), "RELEASE", -1,
                null, now, reason, null, order.tenantId());
        repo.updateRightState(order.id(), "RELEASED");
    }

    private void deductRight(SwapOrderRepository.OrderRow order, LocalDateTime now) {
        if (order == null || !"OCCUPIED".equals(order.rightState())) {
            return;
        }
        SwapOrderRepository.AccountRow account = repo.findAccount(order.userId());
        if (account == null || !repo.deductRight(account.id(), order.userId(), order.id(), now, null,
                order.tenantId())) {
            throw new IllegalStateException("权益实扣失败（无预占可扣）：" + order.orderNo());
        }
        repo.updateRightState(order.id(), "DEDUCTED");
    }
}
