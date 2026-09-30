package com.lrs.buddy.biz.swap.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 后台只读列表（柜机 / 电池资产 / 账实差异）。
 *
 * 三张列表都固定列 + 固定排序 + 上限 size，不接前端传来的列名或 order by：
 * 分页参数可以信，**排序表达式不能信**（那是 SQL 注入面）。
 *
 * 为什么电池与差异不直接用 MyBatis-Plus 的 ORM：这两张表在运营视图里要带关联投影
 * （柜机号、仓号、当前持有人），ORM 生成一堆 wrapper 反而更难读；与接入层/订单层保持一致，
 * 读模型一律走 JdbcTemplate。
 */
@Repository
@RequiredArgsConstructor
public class SwapAdminReadRepository {

    private final JdbcTemplate jdbc;

    public List<Map<String, Object>> cabinets(long tenantId, int limit) {
        return jdbc.queryForList("""
                SELECT c.id, c.cabinet_no, c.site_id, c.slot_count, c.cabinet_model, c.cabinet_state,
                       c.locked_reason, c.last_swap_at, c.create_time,
                       c.device_row_id, d.online_state, d.last_online_at, d.last_offline_at,
                       (SELECT COUNT(*) FROM swap_slot s WHERE s.cabinet_id = c.id AND s.del_flag = 0) AS slot_total,
                       (SELECT COUNT(*) FROM swap_slot s WHERE s.cabinet_id = c.id AND s.del_flag = 0
                            AND s.slot_state = 'IDLE_CHARGING') AS charging_slots,
                       (SELECT COUNT(*) FROM swap_slot s WHERE s.cabinet_id = c.id AND s.del_flag = 0
                            AND s.slot_state IN ('RESERVED_ORDER','FAULT','ISOLATED','DISABLED')) AS unavailable_slots
                FROM swap_cabinet c JOIN iot_device d ON d.id = c.device_row_id AND d.del_flag = 0
                WHERE c.del_flag = 0 AND c.tenant_id = ?
                ORDER BY c.site_id, c.cabinet_no LIMIT ?
                """, tenantId, Math.max(1, Math.min(limit, 200)));
    }

    public List<Map<String, Object>> batteries(String state, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT b.id, b.battery_code, b.product_key, b.battery_state, b.own_type, b.location_state,
                       b.soc, b.soh, b.cycle_count, b.voltage_v, b.capacity_ah, b.fault_code,
                       b.isolated_reason, b.last_full_at, b.last_report_at, b.holder_user_id,
                       c.cabinet_no, s.slot_no
                FROM swap_battery b
                     LEFT JOIN swap_cabinet c ON c.id = b.current_cabinet_id AND c.del_flag = 0
                     LEFT JOIN swap_slot s ON s.id = b.current_slot_id AND s.del_flag = 0
                WHERE b.del_flag = 0
                """);
        List<Object> args = new java.util.ArrayList<>();
        if (state != null && !state.isBlank()) {
            sql.append(" AND b.battery_state = ?");
            args.add(state);
        }
        sql.append(" ORDER BY b.update_time DESC LIMIT ").append(Math.max(1, Math.min(limit, 200)));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> discrepancies(String handleState, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, dedup_key, kind, order_id, cabinet_id, battery_id, user_id,
                       expected_json, actual_json, auto_resolvable, handle_state, remark, create_time
                FROM swap_discrepancy WHERE 1 = 1
                """);
        List<Object> args = new java.util.ArrayList<>();
        if (handleState != null && !handleState.isBlank()) {
            sql.append(" AND handle_state = ?");
            args.add(handleState);
        }
        sql.append(" ORDER BY create_time DESC LIMIT ").append(Math.max(1, Math.min(limit, 200)));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }
}
