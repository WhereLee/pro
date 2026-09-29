package com.lrs.buddy.framework.iot.repo;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 留痕与遥测写入层：message_log / message_dedup / raw_payload / telemetry。
 *
 * 这四张表都是 append-only，不建 version 与 del_flag（swap-ddl.md §5 D-2）：
 * 框架全局 logic-delete 会隐式给查询追加 del_flag=0，那样"历史可被删除"且无人察觉。
 */
public class IngestDao {

    private final JdbcTemplate jdbc;

    public IngestDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertMessageLog(Long id, String direction, String productKey, Long deviceId, String topic,
                                String msgId, Integer qos, String sessionId, String traceId, String payload,
                                boolean valid, String rejectCode, String rejectStep, LocalDateTime occurredAt,
                                long tsMillis, Long tenantId) {
        jdbc.update("""
                INSERT INTO iot_message_log (id, direction, product_key, device_row_id, topic, msg_id, qos,
                        session_id, trace_id, payload, valid_flag, reject_code, reject_step, occurred_at,
                        ts_millis, create_time, tenant_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, direction, productKey, deviceId, topic, msgId, qos, sessionId, traceId, payload,
                valid ? 1 : 0, rejectCode, rejectStep, Timestamp.valueOf(occurredAt), tsMillis,
                Timestamp.valueOf(occurredAt), tenantId);
    }

    /**
     * 去重：插入成功即"首次出现"，撞唯一索引即重复。
     *
     * 用异常控制流是有意的 —— 去重表本身就是幂等的唯一实现手段，
     * 先查后插的写法在 QoS1 并发重投下会有窗口，两个线程都查不到再都插入。
     */
    public boolean markFirstSeen(Long deviceId, String msgId, String topic, String eventType,
                                 LocalDateTime receivedAt, LocalDateTime expireAt, Long tenantId) {
        try {
            jdbc.update("""
                    INSERT INTO iot_msg_dedup (id, device_row_id, msg_id, topic, event_type, received_at,
                            expire_at, create_time, tenant_id)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, System.nanoTime() ^ (long) msgId.hashCode(), deviceId, msgId, topic, eventType,
                    Timestamp.valueOf(receivedAt), Timestamp.valueOf(expireAt), Timestamp.valueOf(receivedAt),
                    tenantId);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    public void insertRawPayload(Long id, Long deviceId, String productKey, String msgId, String topic,
                                 String payload, String reason, String detail, LocalDateTime receivedAt, Long tenantId) {
        jdbc.update("""
                INSERT INTO iot_raw_payload (id, device_row_id, product_key, msg_id, topic, payload, reason,
                        detail, received_at, create_time, tenant_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, id, deviceId, productKey, msgId, topic, payload, reason, detail,
                Timestamp.valueOf(receivedAt), Timestamp.valueOf(receivedAt), tenantId);
    }

    /** 批量写遥测：高频路径，单条 insert 会把接入层打爆。 */
    public int insertTelemetryBatch(List<TelemetryRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        java.util.List<Object[]> args = new java.util.ArrayList<>(rows.size());
        java.util.Date now = new java.util.Date();
        for (TelemetryRow r : rows) {
            args.add(new Object[]{r.id(), r.deviceId(), r.productKey(), r.metricKind(), r.propsJson(),
                    Timestamp.from(java.time.Instant.ofEpochMilli(r.tsMillis())), r.tsMillis(), r.seq(),
                    new java.sql.Timestamp(now.getTime()), r.tenantId()});
        }
        int[] affected = jdbc.batchUpdate("""
                INSERT INTO iot_telemetry (id, device_row_id, product_key, metric_kind, props_json,
                        occurred_at, ts_millis, seq_no, create_time, tenant_id)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, args);
        return affected.length;
    }

    public record TelemetryRow(Long id, Long deviceId, String productKey, String metricKind, String propsJson,
                               long tsMillis, Long seq, Long tenantId) {
    }

    /** 新鲜度窗口内的最新观测（用于在线判定与物模型缺失时的兜底读取）。 */
    public TelemetryRow latestTelemetry(Long deviceId, String metricKind, long sinceTsMillis) {
        List<TelemetryRow> list = jdbc.query("SELECT id, device_row_id, product_key, metric_kind, props_json, "
                + "ts_millis, seq_no, tenant_id FROM iot_telemetry WHERE device_row_id=? AND metric_kind=? "
                + "AND ts_millis >= ? ORDER BY ts_millis DESC LIMIT 1",
                (rs, i) -> new TelemetryRow(rs.getLong("id"), rs.getLong("device_row_id"), rs.getString("product_key"),
                        rs.getString("metric_kind"), rs.getString("props_json"), rs.getLong("ts_millis"),
                        rs.getObject("seq_no") == null ? null : rs.getLong("seq_no"), rs.getLong("tenant_id")),
                deviceId, metricKind, sinceTsMillis);
        return list.isEmpty() ? null : list.get(0);
    }

    public int deleteDedupExpired(LocalDateTime before) {
        return jdbc.update("DELETE FROM iot_msg_dedup WHERE expire_at < ?", Timestamp.valueOf(before));
    }
}
