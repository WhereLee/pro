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
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 资产台账对账与自愈（I7 / M3 阶段 1）。
 *
 * I7 的恒等式不是"数量相等"这种弱断言，而是**归属关系必须双向成立**，三条检查各自抓一类真实事故：
 * <ol>
 *   <li>仓位指向电池、电池不指回仓位 → 电池"在系统里存在但没人知道它在哪儿"，会被分配引擎漏掉。</li>
 *   <li>电池声称在某个仓位、而那个仓位的 battery_id 是空的/是别的电池 → 台账与事实背离，
 *       现场表现为"系统说仓里有电池，运维开仓是空的"。</li>
 *   <li>存在生效绑定但电池状态不是"用户持有" → 用户手上有电池而系统没记账（或反之），
 *       直接影响"一人一电池"与追赔。</li>
 * </ol>
 *
 * 自愈的边界（刻意保守）：只有当**证据完整**时才动手，且动手方式是写进补偿台账交给
 * {@link SwapCompensationExecutor} 执行（幂等、可重试、有留痕），不在这儿直接改表——
 * 对账 job 自己改数据，等于给"谁改的"再增加一条只有日志能回答的旁路。
 * 证据不完整的只记差异台账，等人工或后续域来消化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetLedgerReconcileJob {

    private final JdbcTemplate jdbc;
    private final SwapOrderRepository repo;
    private final MeterRegistry registry;

    /** 每日日终对账（生产可换 cron / 由调度平台触发）；本机与 CI 用 {@link #reconcileOnce()} 直调。 */
    @Scheduled(cron = "${swap.reconcile.cron:0 30 3 * * *}")
    @SchedulerLock(name = "swap-asset-reconcile", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void scheduled() {
        reconcileOnce();
    }

    /** @return 本次发现的差异条数（测试与看板都看这个数，而不是"跑没跑完"） */
    @Transactional
    public int reconcileOnce() {
        LocalDateTime now = LocalDateTime.now();
        int found = 0;

        // 1) 仓位 → 电池：仓说"里面有 b"，电池却不指回这个仓
        // 写 null-safe 的展开形式而不使用 `IS DISTINCT FROM`：MySQL 不支持该写法，
        // 而本项目的迁移与运行 SQL 必须在 H2 与 MySQL 上同一套能跑。
        List<Map<String, Object>> slotOrphan = jdbc.queryForList("""
                SELECT s.id AS slot_id, s.cabinet_id, s.slot_no, s.battery_id
                FROM swap_slot s JOIN swap_battery b ON b.id = s.battery_id AND b.del_flag = 0
                WHERE s.del_flag = 0 AND (b.current_slot_id IS NULL OR b.current_slot_id <> s.id)
                """);
        for (Map<String, Object> row : slotOrphan) {
            found += handle("SLOT_POINTS_NOWHERE", row.get("battery_id"), row.get("slot_id"),
                    "仓位 " + row.get("cabinet_id") + "#" + row.get("slot_no") + " 指向的电池不回指该仓", now);
        }

        // 2) 电池 → 仓位：电池说"我在某个仓"，那个仓里其实没有它
        List<Map<String, Object>> batteryOrphan = jdbc.queryForList("""
                SELECT b.id AS battery_id, b.current_slot_id
                FROM swap_battery b LEFT JOIN swap_slot s ON s.id = b.current_slot_id AND s.del_flag = 0
                WHERE b.del_flag = 0 AND b.current_slot_id IS NOT NULL
                  AND (s.id IS NULL OR s.battery_id IS NULL OR s.battery_id <> b.id)
                """);
        for (Map<String, Object> row : batteryOrphan) {
            found += handle("BATTERY_POINTS_NOWHERE", row.get("battery_id"), row.get("current_slot_id"),
                    "电池声称所在仓位已不含它", now);
        }

        // 3) 生效绑定 ↔ 电池状态必须同真同假
        List<Map<String, Object>> bindingMismatch = jdbc.queryForList("""
                SELECT bd.id AS binding_id, bd.battery_id, bd.user_id, bd.order_id
                FROM swap_battery_binding bd JOIN swap_battery b ON b.id = bd.battery_id AND b.del_flag = 0
                WHERE bd.bind_state = 'ACTIVE' AND b.battery_state <> 'HELD_BY_USER'
                """);
        for (Map<String, Object> row : bindingMismatch) {
            Long batteryId = idOf(row.get("battery_id"));
            // 电池确实在柜里 = 证据完整，可以自动解绑（走补偿执行器，不在这儿直接改）
            boolean inCabinet = batteryId != null && jdbc.queryForObject(
                    "SELECT COUNT(*) FROM swap_battery WHERE id = ? AND current_slot_id IS NOT NULL "
                            + "AND battery_state LIKE 'IN_CABINET%'", Integer.class, batteryId) > 0;
            if (inCabinet) {
                insertUnbindCompensation(row, now);
                found += handle("BINDING_STILL_ACTIVE_BATTERY_IN_CABINET", batteryId, row.get("binding_id"),
                        "生效绑定仍在但电池已回到柜内，已生成解绑补偿", now);
            } else {
                found += handle("BINDING_STATE_MISMATCH", batteryId, row.get("binding_id"),
                        "生效绑定与电池状态不一致且证据不足，转人工核销", now);
            }
        }

        if (found > 0) {
            log.warn("资产对账发现差异 {} 条（证据完整的已排入补偿，其余转人工）", found);
        }
        return found;
    }

    private int handle(String kind, Object batteryId, Object slotOrBindingId, String detail, LocalDateTime now) {
        String dedup = "ASSET:" + kind + ":" + (batteryId == null ? "0" : batteryId)
                + ":" + (slotOrBindingId == null ? "0" : slotOrBindingId);
        boolean inserted = repo.insertDiscrepancy(IdWorker.getId(), dedup, "ASSET_LEDGER", null, null,
                batteryId == null ? null : ((Number) batteryId).longValue(), null, null, detail, detail, 1L);
        registry.counter("assets.ledger.violation", "kind", kind)
                .increment(inserted ? 1 : 0);
        return 1;   // 命中即计数，重复由差异台账的去重键负责不叠加
    }

    private void insertUnbindCompensation(Map<String, Object> row, LocalDateTime now) {
        Number orderId = (Number) row.get("order_id");
        Timestamp ts = Timestamp.valueOf(now);
        jdbc.update("INSERT INTO swap_compensation (id, order_id, action, target_type, target_id, comp_state, "
                        + "attempts, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?,?,?, 'PENDING', 0, ?, ?, 0, 0, 1)",
                IdWorker.getId(), orderId == null ? 0L : orderId.longValue(), "UNBIND_USER_BATTERY", "BATTERY",
                row.get("battery_id"), ts, ts);
    }

    private static Long idOf(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
