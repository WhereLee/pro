package com.lrs.buddy.framework.event;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 表访问。
 *
 * 投递状态放在这张表里而不是消息队列，是为了"发没发"这件事在数据库里可以直接查、可以对账 ——
 * 队列里的消息一旦丢失，业务表里连"曾经打算发过"的痕迹都没有。
 */
public class OutboxDao {

    private final JdbcTemplate jdbc;

    public OutboxDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(Long id, String dedupKey, String aggregateType, Long aggregateId, String eventType,
                      String topic, String payload, String traceId, String state, Integer retryCount,
                      Integer retryMax, Long tenantId) {
    }

    private static final RowMapper<Row> MAPPER = (rs, i) -> new Row(
            rs.getLong("id"), rs.getString("dedup_key"), rs.getString("aggregate_type"), rs.getLong("aggregate_id"),
            rs.getString("event_type"), rs.getString("topic"), rs.getString("payload"), rs.getString("trace_id"),
            rs.getString("outbox_state"), rs.getInt("retry_count"), rs.getInt("retry_max"), rs.getLong("tenant_id"));

    /** 幂等追加：撞唯一索引即视为已存在，返回 false（同一事实不重复投递）。 */
    public boolean append(EventPublisher.Event event, LocalDateTime now) {
        try {
            jdbc.update("""
                    INSERT INTO outbox_event (id, dedup_key, aggregate_type, aggregate_id, event_type, topic,
                            payload, trace_id, outbox_state, retry_count, retry_max, next_retry_at, occurred_at,
                            create_time, update_time, version, tenant_id)
                    VALUES (?,?,?,?,?,?,?,?, 'PENDING', 0, 8, ?,?, ?,?, 0, ?)
                    """, System.nanoTime(), event.dedupKey(), event.aggregateType(), event.aggregateId(),
                    event.eventType(), event.topic(), event.payload(), event.traceId(),
                    Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now), event.tenantId());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public List<Row> scanDue(LocalDateTime now, int limit) {
        return jdbc.query("SELECT id, dedup_key, aggregate_type, aggregate_id, event_type, topic, payload, "
                + "trace_id, outbox_state, retry_count, retry_max, tenant_id FROM outbox_event "
                + "WHERE outbox_state = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= ?) "
                + "ORDER BY id LIMIT " + Math.max(1, Math.min(limit, 500)), MAPPER, Timestamp.valueOf(now));
    }

    public boolean markSent(long id, LocalDateTime now) {
        return jdbc.update("UPDATE outbox_event SET outbox_state='SENT', sent_at=?, update_time=?, "
                + "version=version+1 WHERE id=? AND outbox_state='PENDING'",
                Timestamp.valueOf(now), Timestamp.valueOf(now), id) == 1;
    }

    /** 退避重试：指数退避封顶 5 分钟；超过上限进 DEAD 而不是无限重试。 */
    public boolean scheduleRetry(long id, int retryCount, int retryMax, String error, LocalDateTime now) {
        if (retryCount + 1 >= retryMax) {
            return jdbc.update("UPDATE outbox_event SET outbox_state='DEAD', retry_count=?, last_error=?, "
                    + "update_time=?, version=version+1 WHERE id=?", retryCount + 1, trim(error),
                    Timestamp.valueOf(now), id) == 1;
        }
        long backoffSeconds = Math.min(300L, 1L << Math.min(9, retryCount + 1));
        return jdbc.update("UPDATE outbox_event SET retry_count=?, next_retry_at=?, last_error=?, update_time=?, "
                + "version=version+1 WHERE id=?", retryCount + 1,
                Timestamp.valueOf(now.plusSeconds(backoffSeconds)), trim(error), Timestamp.valueOf(now), id) == 1;
    }

    public long countPending() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE outbox_state = 'PENDING'", Long.class);
        return count == null ? 0 : count;
    }

    public long countDead() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE outbox_state = 'DEAD'", Long.class);
        return count == null ? 0 : count;
    }

    public int deleteSentBefore(LocalDateTime before) {
        return jdbc.update("DELETE FROM outbox_event WHERE outbox_state='SENT' AND sent_at < ?",
                Timestamp.valueOf(before));
    }

    private static String trim(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
