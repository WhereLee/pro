package com.lrs.buddy.framework.iot.security;

import com.lrs.buddy.framework.common.util.CryptoUtil;
import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao.Device;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 设备凭证校验：连接层口令 + 报文层签名。
 *
 * 两处刻意的实现选择：
 * 1 nonce 用 Redis setIfAbsent 做窗口内唯一，而不是查数据库 —— 爆破流量的写入放大必须落在
 *   可过期、可丢弃的存储上；数据库表只在需要持久留痕时才写。
 * 2 校验失败不打印任何密钥材料，也不打印完整 username（含 deviceId，可用于枚举设备）。
 */
@Slf4j
public class DeviceCredentialService {

    private static final String NONCE_KEY_PREFIX = "buddy:iot:nonce:";
    private static final String PWD_FAIL_KEY_PREFIX = "buddy:iot:pwdfail:";

    private final DeviceDirectoryDao deviceDao;
    private final StringRedisTemplate redisTemplate;
    private final DeviceSecrets deviceSecrets;
    private final IotProperties properties;

    public DeviceCredentialService(DeviceDirectoryDao deviceDao, StringRedisTemplate redisTemplate,
                                   DeviceSecrets deviceSecrets, IotProperties properties) {
        this.deviceDao = deviceDao;
        this.redisTemplate = redisTemplate;
        this.deviceSecrets = deviceSecrets;
        this.properties = properties;
    }

    /** 连接 username 的形态：deviceId|ts|nonce。 */
    public record ConnUsername(String deviceId, long ts, String nonce) {
    }

    public ConnUsername parseUsername(String username) {
        if (username == null) {
            return null;
        }
        String[] parts = username.split("\\|");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new ConnUsername(parts[0], Long.parseLong(parts[1]), parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 校验连接口令。productKey 由 clientId（productKey::deviceId）提供。
     *
     * @return 通过时返回设备；否则 null（调用方必须拒绝连接且只计数不落明细）
     */
    public Device authenticateConnection(String clientId, String username, byte[] password) {
        String productKey = clientId == null ? null : beforeDoubleColon(clientId);
        String deviceId = clientId == null ? null : afterDoubleColon(clientId);
        ConnUsername parsed = parseUsername(username);
        if (productKey == null || deviceId == null || parsed == null) {
            return null;
        }
        if (!deviceId.equals(parsed.deviceId())) {
            // username 与 clientId 不一致：拒绝，且不让攻击者从差异中推断正确格式
            recordFailure(deviceId);
            return null;
        }
        long skew = Math.abs(System.currentTimeMillis() - parsed.ts());
        if (skew > properties.getAuthClockWindowSeconds() * 1000L) {
            recordFailure(deviceId);
            return null;
        }
        if (!claimNonce(deviceId, parsed.nonce())) {
            recordFailure(deviceId);
            return null;
        }
        Device device = deviceDao.findDevice(productKey, deviceId);
        if (device == null || device.enabled() == null || device.enabled() != 1) {
            recordFailure(deviceId);
            return null;
        }
        String master = decryptMasterSecret(device.secretCipher());
        if (master == null) {
            log.warn("设备密钥解密失败，拒绝连接：productKey={}, deviceId={}", productKey, deviceId);
            recordFailure(deviceId);
            return null;
        }
        String expected = deviceSecrets.connectionPassword(master, username);
        String actual = password == null ? "" : new String(password, StandardCharsets.UTF_8);
        if (!CryptoUtil.equalsConstantTime(expected, actual)) {
            recordFailure(deviceId);
            return null;
        }
        return device;
    }

    /** 取设备报文签名用的主密钥明文（调用方用完即弃，不得缓存到字段或日志）。 */
    public String masterSecretOf(Device device) {
        return decryptMasterSecret(device.secretCipher());
    }

    public String messageSecretOf(Device device) {
        String master = decryptMasterSecret(device.secretCipher());
        return master == null ? null : deviceSecrets.deriveMsgSecret(master);
    }

    private String decryptMasterSecret(String cipher) {
        String key = properties.secretKeyBytes();
        if (key == null) {
            log.error("未配置 buddy.iot.device-secret-key，无法校验设备签名");
            return null;
        }
        try {
            return CryptoUtil.aesGcmDecrypt(key, cipher);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean claimNonce(String deviceId, String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return false;
        }
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(NONCE_KEY_PREFIX + deviceId + ":" + nonce,
                    "1", Duration.ofSeconds(properties.getAuthClockWindowSeconds() * 2L));
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException e) {
            // Redis 不可用时保守拒绝：宁可设备重连，也不能让重放窗口敞开
            log.error("nonce 校验依赖的 Redis 不可用，按拒绝处理：{}", e.getMessage());
            return false;
        }
    }

    private void recordFailure(String deviceId) {
        try {
            redisTemplate.opsForValue().increment(PWD_FAIL_KEY_PREFIX + deviceId);
        } catch (RuntimeException ignored) {
            // 计数失败不能影响拒绝本身
        }
    }

    private static String beforeDoubleColon(String clientId) {
        int idx = clientId.indexOf("::");
        return idx <= 0 ? null : clientId.substring(0, idx);
    }

    private static String afterDoubleColon(String clientId) {
        int idx = clientId.indexOf("::");
        return idx < 0 || idx + 2 >= clientId.length() ? null : clientId.substring(idx + 2);
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
