package com.lrs.buddy.biz.swap.recovery;

import com.lrs.buddy.biz.swap.compensation.CompensationAction;
import com.lrs.buddy.biz.swap.flow.SwapFlowService;
import com.lrs.buddy.biz.swap.order.OrderState;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 重启现场重建（FI-16：云侧实例在等应答时重启，不得留下永久悬挂订单）。
 *
 * 只做三件事，且**绝不改状态机语义**：
 * <ol>
 *   <li><b>补 deadline</b>：非终态却没有 deadline 的单，超时驱动永远扫不到它，
 *       于是"挂着不动"变成"永久悬挂"——这是 FI-16 的真正形状。按当前态的驻留上限补上即可，
 *       后续收敛仍由超时驱动负责（重建不等于接管）。</li>
 *   <li><b>收尾待补偿的 ABORTING 单</b>：阻塞补偿已做完的单，由这里落终态；
 *       还没做完的保持 ABORTING（不能因为"重启了"就跳过 I8）。</li>
 *   <li><b>孤立指令只留痕不删</b>：指令在途而订单已终态时，删掉指令等于销毁证据——
 *       现场最需要的是"为什么这条指令还在跑"。所以记差异 + 计数，处置交给对账与人工。</li>
 * </ol>
 *
 * 幂等：每步都以 CAS/条件更新为前置，重复执行不叠加效果（启动钩子可能被多次触发）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwapRecoveryService {

    private static final List<String> BLOCKING = Arrays.stream(CompensationAction.values())
            .filter(CompensationAction::blocking).map(Enum::name).toList();

    private final SwapOrderRepository repo;
    private final SwapFlowService flow;
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;

    /** 启动即重建一次（CI 与本机也可靠单测直调 {@link #recoverOnce()}，不必等事件）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onBoot() {
        RecoveryResult result = recoverOnce();
        if (result.deadlinesRestored() > 0 || result.ordersFinalized() > 0 || result.orphanCommands() > 0) {
            log.warn("重启现场重建：补 deadline {} 条、收尾 ABORTING {} 条、孤立指令 {} 条",
                    result.deadlinesRestored(), result.ordersFinalized(), result.orphanCommands());
        }
    }

    public record RecoveryResult(int deadlinesRestored, int ordersFinalized, int ordersStillWaiting,
                                 int orphanCommands) {
    }

    public RecoveryResult recoverOnce() {
        LocalDateTime now = LocalDateTime.now();
        int restored = 0;

        for (Map<String, Object> row : repo.ordersStuckWithoutDeadline()) {
            long orderId = ((Number) row.get("id")).longValue();
            OrderState state = OrderState.valueOf(String.valueOf(row.get("order_state")));
            long deadlineTs = now.plusSeconds(Math.max(1, state.maxDwellSeconds()))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            repo.setDeadline(orderId, deadlineTs, now.plusSeconds(Math.max(1, state.maxDwellSeconds())), now);
            restored++;
        }

        int finalized = 0;
        int stillWaiting = 0;
        for (Long orderId : repo.ordersAwaitingCompensation()) {
            if (repo.countOpenCompensation(orderId, BLOCKING) > 0) {
                stillWaiting++;
                continue;
            }
            try {
                flow.finishAborting(orderId);
                finalized++;
            } catch (RuntimeException e) {
                // 收尾失败不改判：单仍停在 ABORTING，由下一轮或人工处理
                registry.counter("recovery.finalize_failed").increment();
                log.error("重启收尾失败：order={}, err={}", orderId, e.getMessage());
            }
        }

        Integer orphans = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iot_command c JOIN swap_order o ON o.id = c.biz_id
                WHERE c.biz_type = 'SWAP_ORDER'
                  AND c.cmd_state IN ('CREATED','DISPATCHED','ACKED','UNCONFIRMED')
                  AND o.order_state IN ('COMPLETED','REJECTED','ABORTED','FAILED_MANUAL')
                """, Integer.class);
        int orphanCount = orphans == null ? 0 : orphans;
        if (orphanCount > 0) {
            registry.counter("recovery.orphan_commands", "count", String.valueOf(orphanCount)).increment();
            log.warn("发现 {} 条在途指令挂在已终态订单上（保留不动，交对账与人工处置）", orphanCount);
        }
        return new RecoveryResult(restored, finalized, stillWaiting, orphanCount);
    }
}
