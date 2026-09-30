package com.lrs.buddy.framework.iot.repo;

import com.lrs.buddy.framework.iot.command.CommandState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 指令表访问。
 *
 * 状态迁移一律带 fromState 谓词（CAS）而不是"读出来改完写回去"：
 * 迟到应答、超时任务、重发请求三者在不同线程上可能同时命中同一条指令，
 * 无谓词的写法会让后到的覆盖先到的，且没有任何报错痕迹 —— 这类 bug 线上极难归因。
 */
public class CommandDao {

    private final JdbcTemplate jdbc;

    public CommandDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(Long id, String cmdId, String bizType, Long bizId, Integer stepNo, Long deviceRowId,
                      String productKey, String topic, String cmdCode, String payloadJson, Integer qos,
                      CommandState state, Integer retryLeft, Integer ttlSec, String sessionId, String traceId,
                      Long sentTs, Long deadlineTs, String replyCode, Long tenantId) {
    }

    private static final RowMapper<Row> MAPPER = (rs, i) -> new Row(
            rs.getLong("id"), rs.getString("cmd_id"), rs.getString("biz_type"), rs.getLong("biz_id"),
            rs.getInt("step_no"), rs.getLong("device_row_id"), rs.getString("product_key"), rs.getString("topic"),
            rs.getString("cmd_code"), rs.getString("payload_json"), rs.getInt("qos"),
            CommandState.valueOf(rs.getString("cmd_state")), rs.getInt("retry_left"), rs.getInt("ttl_sec"),
            rs.getString("session_id"), rs.getString("trace_id"),
            rs.getObject("sent_ts") == null ? null : rs.getLong("sent_ts"),
            rs.getObject("deadline_ts") == null ? null : rs.getLong("deadline_ts"),
            rs.getString("reply_code"), rs.getLong("tenant_id"));

    private static final String COLUMNS = "id, cmd_id, biz_type, biz_id, step_no, device_row_id, product_key, "
            + "topic, cmd_code, payload_json, qos, cmd_state, retry_left, ttl_sec, session_id, trace_id, "
            + "sent_ts, deadline_ts, reply_code, tenant_id";

    /**
     * 落一条在途指令。
     *
     * 撞唯一索引 active_step 表示"这一步已经有一条在途指令"，
     * 调用方必须先把它置 SUPERSEDED 再重发 —— 这是协议 §4.2 的重发前置条件，
     * 所以这里抛异常而不是返回 false：静默失败会诱使调用方以为发出去了。
     */
    public Long insert(Row row, LocalDateTime now) {
        long id = System.nanoTime() ^ (long) row.cmdId().hashCode() * 31;
        try {
            jdbc.update("""
                    INSERT INTO iot_command (id, cmd_id, biz_type, biz_id, step_no, device_row_id, product_key,
                            topic, cmd_code, payload_json, qos, priority, cmd_state, retry_left, retry_max,
                            ttl_sec, session_id, trace_id, expire_at, deadline_ts, create_time, update_time,
                            version, del_flag, tenant_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 100, 'CREATED',
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?)
                    """, id, row.cmdId(), row.bizType(), row.bizId(), row.stepNo(), row.deviceRowId(),
                    row.productKey(), row.topic(), row.cmdCode(), row.payloadJson(), row.qos(), row.retryLeft(),
                    row.retryLeft(), row.ttlSec(), row.sessionId(), row.traceId(),
                    Timestamp.valueOf(now.plusSeconds(row.ttlSec())), row.deadlineTs(),
                    Timestamp.valueOf(now), Timestamp.valueOf(now), row.tenantId());
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("业务步骤已有在途指令，需先置 SUPERSEDED 再重发：biz="
                    + row.bizType() + "#" + row.bizId() + "#" + row.stepNo(), e);
        }
        return id;
    }

    public Row findByCmdId(String cmdId) {
        List<Row> list = jdbc.query("SELECT " + COLUMNS + " FROM iot_command WHERE cmd_id = ?", MAPPER, cmdId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Row> findInFlightByStep(String bizType, long bizId, int stepNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM iot_command WHERE biz_type = ? AND biz_id = ? "
                + "AND step_no = ? AND cmd_state IN ('CREATED','DISPATCHED','ACKED','UNCONFIRMED')",
                MAPPER, bizType, bizId, stepNo);
    }

    /**
     * CAS 状态迁移。返回 false 表示当前状态已经不是 fromState（并发下被别人抢先，或重复投递）。
     */
    public boolean transition(long id, CommandState from, CommandState to, LocalDateTime now,
                              String replyCode, String replyJson, String failReason, Long deadlineTs) {
        if (!from.canGoTo(to)) {
            throw new IllegalArgumentException("非法指令状态迁移 " + from + " -> " + to);
        }
        return jdbc.update("""
                UPDATE iot_command SET cmd_state = ?, update_time = ?, version = version + 1,
                       sent_ts   = CASE WHEN ? = 'DISPATCHED' THEN ? ELSE sent_ts END,
                       ack_at    = CASE WHEN ? IN ('ACKED','CONFIRMED','UNCONFIRMED') AND ack_at IS NULL
                                        THEN ? ELSE ack_at END,
                       confirmed_at = CASE WHEN ? = 'CONFIRMED' THEN ? ELSE confirmed_at END,
                       deadline_ts = COALESCE(?, deadline_ts),
                       reply_code  = COALESCE(?, reply_code),
                       reply_json  = COALESCE(?, reply_json),
                       fail_reason = COALESCE(?, fail_reason)
                WHERE id = ? AND cmd_state = ?
                """, to.name(), Timestamp.valueOf(now), to.name(), now.atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli(), to.name(), Timestamp.valueOf(now), to.name(), Timestamp.valueOf(now),
                deadlineTs, replyCode, replyJson, failReason, id, from.name()) == 1;
    }

    /** 兜底扫描：已到 deadline 且仍在途的指令（协议 §7.4）。 */
    public List<Row> scanDue(long nowTs, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM iot_command "
                + "WHERE cmd_state IN ('CREATED','DISPATCHED','ACKED','UNCONFIRMED') AND deadline_ts <= ? "
                + "ORDER BY deadline_ts LIMIT " + Math.max(1, Math.min(limit, 500)), MAPPER, nowTs);
    }

    public int decrementRetry(long id) {
        return jdbc.update("UPDATE iot_command SET retry_left = retry_left - 1, update_time = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND retry_left > 0", id);
    }

    public long countInFlight() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM iot_command "
                + "WHERE cmd_state IN ('CREATED','DISPATCHED','ACKED','UNCONFIRMED')", Long.class);
        return count == null ? 0 : count;
    }
}
