package com.lrs.buddy.biz.swap.provision;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lrs.buddy.framework.common.util.CryptoUtil;
import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.entity.IotDevice;
import com.lrs.buddy.framework.iot.mapper.IotDeviceMapper;
import com.lrs.buddy.framework.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 设备开通与凭证颁发（M2-0b）。
 *
 * 为什么这段必须存在而不是"种子插几条设备"就能跑：
 * 主链路要下发指令，指令要签名，签名要主密钥。如果密钥是种子里写死的字符串，
 * 那么 M2 的 E2E 证明的是"我造的假数据能跑"，而不是"设备真的能通过注册拿到凭证并认证成功"。
 * 本服务让"开通 → 拿密钥 → 首连认证"这条链在测试里被真实走一遍。
 *
 * 三条口径：
 * 1 **明文密钥只在注册/轮转的响应里出现一次**，库里只有 AES-GCM 密文。
 *   理由：能再次读出明文就等于"任何有查询权限的人都能导出全量设备密钥"，泄露面从"运维导出"扩大到"读接口"。
 *   忘记密钥的正确处置是轮转，不是找回。
 * 2 日志只打 deviceId 与 secretVersion，绝不打密钥或其派生值。
 * 3 轮转后旧密钥**立即失效**（单版本）。代价写清楚：正在连接的柜机这一拍会认证失败并重连；
 *   企业常见的"双版本宽限期"需要 connSecret 支持多版本并存，等 M5 批量运维（一次轮转上百台）落地时再补，
 *   因为那才是它真正被需要的场景——现在加等于给没有需求的路径增加状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceProvisionService {

    /** deviceId 口径：可读、可进主题、不要出现 MQTT 通配符与分隔符冲突字符。 */
    private static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9_-]{4,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IotDeviceMapper deviceMapper;
    private final JdbcTemplate jdbc;
    private final IotProperties iotProperties;

    /** 开通结果：masterSecret 是一次性明文，任何后续接口都拿不到它。 */
    public record Credential(Long deviceRowId, String productKey, String deviceId, String masterSecret,
                             int secretVersion) {
    }

    @Transactional
    public Credential register(String productKey, String deviceId, String deviceName) {
        if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
            throw new IllegalArgumentException("deviceId 不合法（要求 4-64 位字母/数字/-/_）：" + deviceId);
        }
        if (countProduct(productKey) == 0) {
            throw new IllegalArgumentException("产品不存在或未启用：" + productKey);
        }
        if (findId(productKey, deviceId) != null) {
            throw new IllegalStateException("设备已存在：" + productKey + "/" + deviceId);
        }
        String masterSecret = generateSecret();
        Long id = insertDevice(productKey, deviceId, deviceName, masterSecret, 1);
        log.info("设备已开通：productKey={}, deviceId={}, rowId={}, secretVersion=1", productKey, deviceId, id);
        return new Credential(id, productKey, deviceId, masterSecret, 1);
    }

    /** 轮转：生成新主密钥并覆盖密文。返回一次性新明文，旧密钥此刻起不再能通过认证。 */
    @Transactional
    public Credential rotate(String productKey, String deviceId) {
        Long id = findId(productKey, deviceId);
        if (id == null) {
            throw new IllegalArgumentException("设备不存在：" + productKey + "/" + deviceId);
        }
        String masterSecret = generateSecret();
        jdbc.update("UPDATE iot_device SET secret_cipher = ?, secret_version = secret_version + 1, "
                        + "update_time = ?, version = version + 1 WHERE id = ?",
                CryptoUtil.aesGcmEncrypt(iotProperties.getDeviceSecretKey(), masterSecret),
                Timestamp.valueOf(LocalDateTime.now()), id);
        Integer version = jdbc.queryForObject("SELECT secret_version FROM iot_device WHERE id = ?",
                Integer.class, id);
        log.info("设备密钥已轮转：deviceId={}, secretVersion={}", deviceId, version);
        return new Credential(id, productKey, deviceId, masterSecret, version == null ? 1 : version);
    }

    public IotDevice find(String productKey, String deviceId) {
        Long id = findId(productKey, deviceId);
        return id == null ? null : deviceMapper.selectById(id);
    }

    /** 启停：停用后接入层不再为其建立会话（认证直接失败）。 */
    @Transactional
    public void toggleEnabled(Long deviceRowId, boolean enabled) {
        IotDevice device = deviceMapper.selectById(deviceRowId);
        if (device == null) {
            throw new IllegalArgumentException("设备不存在：" + deviceRowId);
        }
        device.setEnabled(enabled ? 1 : 0);
        deviceMapper.updateById(device);
    }

    private Long insertDevice(String productKey, String deviceId, String deviceName, String masterSecret,
                              int secretVersion) {
        long id = IdWorker.getId();
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbc.update("""
                    INSERT INTO iot_device (id, product_key, device_id, device_name, secret_cipher, secret_version,
                            online_state, enabled, activated_at, create_time, update_time, version, del_flag, tenant_id)
                    VALUES (?,?,?,?,?,?, 'UNKNOWN', 1, ?,?, ?, 0, 0, ?)
                    """, id, productKey, deviceId,
                    deviceName == null || deviceName.isBlank() ? deviceId : deviceName,
                    CryptoUtil.aesGcmEncrypt(iotProperties.getDeviceSecretKey(), masterSecret), secretVersion,
                    Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now),
                    TenantContext.getTenantId());
        } catch (DuplicateKeyException e) {
            // 唯一索引是最终裁判：先查后插只是为了给可读错误，并发下真撞了必须转成业务异常而不是 500
            throw new IllegalStateException("设备已存在：" + productKey + "/" + deviceId, e);
        }
        return id;
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private int countProduct(String productKey) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM iot_product WHERE product_key = ? "
                + "AND enabled = 1 AND del_flag = 0", Integer.class, productKey);
        return count == null ? 0 : count;
    }

    private Long findId(String productKey, String deviceId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM iot_device WHERE product_key = ? AND device_id = ?",
                Long.class, productKey, deviceId);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
