package com.lrs.buddy.biz.swap.safety;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrs.buddy.biz.swap.flow.SwapFlowService;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 安全联动（M3）：一次安全事件 → 停充 + 锁柜 + 在途单批量走自动中止路径 + 告警升级挂账。
 *
 * 三处刻意的做法：
 * <ul>
 *   <li><b>逐台柜机独立提交</b>：一台柜机连不上不能让整个站点的联动半途而废
 *       ——安全动作的失败必须是可见的单独一条，而不是“整个操作回滚”造成的假干净。
 *       因此这里**不开外层事务**：外层 `@Transactional` + 内部 try/catch 是假的隔离——
 *       内层一抛就把整个事务标成 rollback-only，方法照样返回“成功”，而锁柜与中止全被回滚。</li>
 *   <li><b>在途单走 {@code ALARM_SAFETY_LOCK}（系统自动）而不是 {@code ADMIN_ABORT}（人工）</b>：
 *       前者只能进 ABORTING 并做补偿，绝不直接落资金终态；两条路径在状态机上分开，
 *       事后才能回答“这单是被安全规则停掉的，还是被运营停掉的”。</li>
 *   <li><b>告警升级挂补偿台账</b>：告警引擎属 M5，今天没有执行者。写 PENDING 而不是“记个日志”，
 *       因为日志会滚掉，台账不会。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwapSafetyLinkageService {

    private static final String BIZ_TYPE = "SWAP_SAFETY";

    private final SwapOrderRepository repo;
    private final SwapFlowService flow;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final MeterRegistry registry;
    private final TransactionTemplate txTemplate;
    private final com.lrs.buddy.framework.iot.command.DeviceCommandService commands;

    /** 联动结果：每一步做了多少、哪几步没做成（全部返回给调用方与日志，不吞）。 */
    public record LinkageResult(int cabinets, int stopCommandsSent, int stopCommandsFailed,
                                int ordersAborted, int ordersWaitingCompensation, int ordersAbortFailed,
                                String alarmRef) {
    }

    /**
     * 站点级紧急停充 + 锁柜 + 在途单中止。
     *
     * @param reason 必须写清触发依据（哪个告警、哪块电池温度），联动本身也是要被审计的动作
     */
    public LinkageResult emergencyStopSite(long siteId, String reason, String alarmRef) {
        if (reason == null || reason.trim().length() < 5) {
            throw new IllegalArgumentException("安全联动必须写明依据");
        }
        List<Map<String, Object>> cabinets = repo.cabinetsAtSite(siteId);
        if (cabinets.isEmpty()) {
            // 空站点与“联动成功”不能共用一个返回值：调用方必须能区分“没柜可停”和“都停了”
            log.warn("安全联动未命中任何柜机：site={}, 依据={}", siteId, reason);
        }
        LocalDateTime now = LocalDateTime.now();
        int sent = 0;
        int failed = 0;
        int aborted = 0;
        int waiting = 0;
        int abortFailed = 0;

        for (Map<String, Object> cabinet : cabinets) {
            long cabinetId = ((Number) cabinet.get("id")).longValue();
            // 锁柜 + 停充：一台一个事务，失败只影响这一台
            try {
                txTemplate.executeWithoutResult(status -> {
                    repo.lockCabinet(cabinetId, reason, now);
                    registry.counter("swap.safety.locked").increment();
                    dispatchStop(cabinet, reason);
                });
                sent++;
            } catch (RuntimeException e) {
                failed++;
                registry.counter("swap.safety.stop_failed").increment();
                log.error("紧急停充联动失败（锁柜与停充均回滚）：cabinet={}, err={}", cabinet.get("cabinet_no"), e.getMessage());
                // 挂一条升级告警到台账：这台柜现在既没锁也没停充，必须有人知道
                try {
                    repo.insertCompensation(IdWorker.getId(), 0L, "ESCALATE_ALARM", "CABINET", cabinetId,
                            "PENDING", now, 1L);
                } catch (RuntimeException cascade) {
                    registry.counter("swap.safety.escalate_failed").increment();
                    log.error("停充失败后的告警升级也挂不上：cabinet={}, err={}",
                            cabinet.get("cabinet_no"), cascade.getMessage());
                }
            }

            // 在途单逐单提交：一单卡在 guard 上不能挡住同柜其余单
            for (SwapOrderRepository.OrderRow order : repo.findInfightByCabinet(cabinetId)) {
                try {
                    flow.safetyAbort(order.id(), reason);
                    String state = repo.findOrder(order.id()).state();
                    if ("ABORTING".equals(state)) {
                        waiting++;
                    } else {
                        aborted++;
                    }
                } catch (RuntimeException e) {
                    abortFailed++;
                    registry.counter("swap.safety.abort_failed").increment();
                    log.error("安全联动中止在途单失败：order={}, err={}", order.orderNo(), e.getMessage());
                }
            }
        }

        if (alarmRef != null && !alarmRef.isBlank()) {
            try {
                jdbc.update("INSERT INTO swap_compensation (id, order_id, action, target_type, target_id, comp_state, "
                                + "attempts, remark, create_time, update_time, version, del_flag, tenant_id) "
                                + "VALUES (?, 0, 'ESCALATE_ALARM', 'ORDER', NULL, 'PENDING', 0, ?, ?, ?, 0, 0, 1)",
                        IdWorker.getId(), "alarm=" + alarmRef + "; " + reason,
                        java.sql.Timestamp.valueOf(now), java.sql.Timestamp.valueOf(now));
            } catch (RuntimeException e) {
                registry.counter("swap.safety.alarm_ledger_failed").increment();
                log.error("告警升级挂账失败：alarm={}, err={}", alarmRef, e.getMessage());
                throw e;   // 这条不能吞：没挂上账的告警升级就是没人收到的告警
            }
        }
        log.warn("安全联动完成：site={}, 柜={}, 停充成功/失败={}/{}, 在途单已中止/待补偿收尾/中止失败={}/{}/{}, 依据={}",
                siteId, cabinets.size(), sent, failed, aborted, waiting, abortFailed, reason);
        return new LinkageResult(cabinets.size(), sent, failed, aborted, waiting, abortFailed, alarmRef);
    }

    private void dispatchStop(Map<String, Object> cabinet, String reason) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("reason", reason);
        data.put("scope", "SITE");
        long cabinetId = ((Number) cabinet.get("id")).longValue();
        // 重发前置：同一柜机上一次的停充指令还在途时，`uk_icmd_active` 会拒掉新指令。
        // 不先置 SUPERSEDED 的话，第二次、第三次安全联动全都发不出去（异常被上层 catch 吞掉，
        // 看起来“联动跑完了”，柜机其实只收到过一次停充）—— 这是 M2 超时驱动踩过的同一个坑。
        commands.supersedeInFlight(BIZ_TYPE, cabinetId, 0);
        commands.issue(new com.lrs.buddy.framework.iot.command.DeviceCommandService.Issue(
                BIZ_TYPE, cabinetId, 0,
                ((Number) cabinet.get("device_row_id")).longValue(),
                String.valueOf(cabinet.get("product_key")), String.valueOf(cabinet.get("device_id")),
                // qos=1、ttl=10s、retryMax=0：三个值全部照协议 §4.1 的 EMERGENCY_STOP 行取，不自己“调一个觉得合适的值”：
                // 副作用指令不自动重试是铁律；而 ttl 拖长了只会让一条早该作废的停充挂在离线设备上等重发。
                "EMERGENCY_STOP", data, 1, 10, 0, true, null, null, 1L));
    }
}
