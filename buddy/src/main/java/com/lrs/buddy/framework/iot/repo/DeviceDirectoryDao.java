package com.lrs.buddy.framework.iot.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 设备目录与品类的 JDBC 访问层。
 *
 * 为什么接入层不用 MyBatis-Plus 实体（与 biz 侧不同）：
 * 1 高频写入路径需要显式批量 SQL，不想被自动填充与拦截器插入不确定行为；
 * 2 框架全局 logic-delete-field=delFlag 会给任何带 del_flag 的查询追加过滤条件，
 *   而留痕类表必须能查到历史（swap-ddl.md §5 D-2）；
 * 3 iot_telemetry 是复合主键，MP 的单主键假设不适配。
 */
public class DeviceDirectoryDao {

    private final JdbcTemplate jdbc;

    public DeviceDirectoryDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 设备目录行。secretCipher 是密文，派生密钥在内存完成，绝不落日志。 */
    public record Device(Long id, String productKey, String deviceId, String gatewayRowId, String secretCipher,
                        Integer secretVersion, String onlineState, Long tenantId, Long lastSeenTs, Integer enabled) {
    }

    /** 品类行：门槛与新鲜度窗口的默认值所有者。 */
    public record Product(String productKey, String category, Integer minSoc, BigDecimal maxAllocTemp,
                          BigDecimal maxChargeTemp, Integer telemetryFreshSec, Integer locateFreshSec,
                          Integer heartbeatSec, Integer modelVersion) {
    }

    public record Shadow(Long id, Long deviceId, String desiredJson, String reportedJson,
                         Long desiredVer, Long reportedVer, String syncState) {
    }

    private static final RowMapper<Device> DEVICE_MAPPER = (rs, i) -> new Device(
            rs.getLong("id"), rs.getString("product_key"), rs.getString("device_id"),
            nullableString(rs, "gateway_row_id"), rs.getString("secret_cipher"),
            rs.getInt("secret_version"), rs.getString("online_state"),
            rs.getLong("tenant_id"), nullableLong(rs, "last_seen_ts"), rs.getInt("enabled"));

    private static final RowMapper<Product> PRODUCT_MAPPER = (rs, i) -> new Product(
            rs.getString("product_key"), rs.getString("category"), rs.getInt("min_soc"),
            rs.getBigDecimal("max_alloc_temp"), rs.getBigDecimal("max_charge_temp"),
            rs.getInt("telemetry_fresh_sec"), rs.getInt("locate_fresh_sec"),
            rs.getInt("heartbeat_sec"), rs.getInt("model_version"));

    public Device findDevice(String productKey, String deviceId) {
        List<Device> list = jdbc.query("""
                SELECT id, product_key, device_id, gateway_row_id, secret_cipher, secret_version,
                       online_state, tenant_id, last_seen_ts, enabled
                FROM iot_device WHERE product_key = ? AND device_id = ? AND del_flag = 0
                """, DEVICE_MAPPER, productKey, deviceId);
        return list.isEmpty() ? null : list.get(0);
    }

    public Device findDeviceById(Long id) {
        List<Device> list = jdbc.query("""
                SELECT id, product_key, device_id, gateway_row_id, secret_cipher, secret_version,
                       online_state, tenant_id, last_seen_ts, enabled
                FROM iot_device WHERE id = ? AND del_flag = 0
                """, DEVICE_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public Product findProduct(String productKey) {
        List<Product> list = jdbc.query("""
                SELECT product_key, category, min_soc, max_alloc_temp, max_charge_temp,
                       telemetry_fresh_sec, locate_fresh_sec, heartbeat_sec, model_version
                FROM iot_product WHERE product_key = ? AND del_flag = 0
                """, PRODUCT_MAPPER, productKey);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 记录一次连接建立：先把该设备上一条 ACTIVE 会话置 EXPIRED，再插入新会话。
     *
     * 顺序不能反：active_device 生成列上的唯一索引会让"两条 ACTIVE 并存"直接失败，
     * 而失败发生在这一步是好事 —— 它挡住了同一设备两个会话同时有效（强制下线只能踢掉一个）。
     */
    public int openSession(Long deviceId, String sessionId, String nodeId, Integer keepaliveSec,
                           Integer cleanStart, LocalDateTime now, Long tenantId) {
        jdbc.update("UPDATE iot_device_session SET conn_state = 'EXPIRED', disconnected_at = ? "
                + "WHERE device_row_id = ? AND conn_state = 'ACTIVE'", Timestamp.valueOf(now), deviceId);
        return jdbc.update("""
                INSERT INTO iot_device_session (id, device_row_id, session_id, node_id, keepalive_sec,
                        clean_start, conn_state, connected_at, last_hb_ts, create_time, update_time,
                        version, del_flag, tenant_id)
                VALUES (?,?,?,?,?,?, 'ACTIVE', ?,?, ?, ?, 0, 0, ?)
                """, System.nanoTime() + deviceId, deviceId, sessionId, nodeId, keepaliveSec, cleanStart,
                Timestamp.valueOf(now), toEpochMilli(now), Timestamp.valueOf(now), Timestamp.valueOf(now), tenantId);
    }

    public int closeSession(String sessionId, String reason, LocalDateTime now) {
        return jdbc.update("UPDATE iot_device_session SET conn_state='CLOSED', disconnected_at=?, close_reason=?, "
                + "update_time=? WHERE session_id=? AND conn_state='ACTIVE'",
                Timestamp.valueOf(now), reason, Timestamp.valueOf(now), sessionId);
    }

    public int touchHeartbeat(String sessionId, long tsMillis) {
        return jdbc.update("UPDATE iot_device_session SET last_hb_ts=? WHERE session_id=? AND conn_state='ACTIVE'",
                tsMillis, sessionId);
    }

    public String activeSessionId(Long deviceId) {
        List<String> list = jdbc.queryForList(
                "SELECT session_id FROM iot_device_session WHERE device_row_id=? AND conn_state='ACTIVE'",
                String.class, deviceId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 更新设备在线态与最后可见时间；返回影响行数，0 表示并发下已被其他节点改写。 */
    public int updateOnlineState(Long deviceId, String onlineState, Long lastSeenTs, LocalDateTime now,
                                 String offlineReason) {
        return jdbc.update("UPDATE iot_device SET online_state=?, last_seen_ts=?, update_time=?, "
                        + "last_online_at=COALESCE(?, last_online_at), offline_reason=? WHERE id=?",
                onlineState, lastSeenTs, Timestamp.valueOf(now),
                "ONLINE".equals(onlineState) ? Timestamp.valueOf(now) : null, offlineReason, deviceId);
    }

    public List<Device> devicesNotOffline() {
        return jdbc.query("""
                SELECT id, product_key, device_id, gateway_row_id, secret_cipher, secret_version,
                       online_state, tenant_id, last_seen_ts, enabled
                FROM iot_device WHERE del_flag = 0 AND enabled = 1 AND online_state <> 'OFFLINE'
                """, DEVICE_MAPPER);
    }

    public Shadow findShadow(Long deviceId) {
        List<Shadow> list = jdbc.query("SELECT id, device_row_id, desired_json, reported_json, desired_ver, "
                + "reported_ver, sync_state FROM iot_shadow WHERE device_row_id = ?",
                (rs, i) -> new Shadow(rs.getLong("id"), rs.getLong("device_row_id"), rs.getString("desired_json"),
                        rs.getString("reported_json"), rs.getLong("desired_ver"), rs.getLong("reported_ver"),
                        rs.getString("sync_state")), deviceId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void upsertShadowReported(Long deviceId, String reportedJson, long reportedVer, String syncState,
                                      LocalDateTime now) {
        int updated = jdbc.update("UPDATE iot_shadow SET reported_json=?, reported_ver=?, sync_state=?, "
                + "update_time=?, version=version+1 WHERE device_row_id=?", reportedJson, reportedVer, syncState,
                Timestamp.valueOf(now), deviceId);
        if (updated == 0) {
            jdbc.update("INSERT INTO iot_shadow (id, device_row_id, reported_json, reported_ver, desired_ver, "
                    + "sync_state, create_time, update_time, version, del_flag) VALUES (?,?,?,?,0,?,?,?,0,0)",
                    Objects.hash(deviceId, "shadow") * 31L + 7, deviceId, reportedJson, reportedVer, syncState,
                    Timestamp.valueOf(now), Timestamp.valueOf(now));
        }
    }

    private static String nullableString(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : String.valueOf(value);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static long toEpochMilli(LocalDateTime time) {
        return time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
