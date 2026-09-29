package com.lrs.buddy.framework.iot.security;

import com.lrs.buddy.framework.common.util.CryptoUtil;
import com.lrs.buddy.framework.iot.config.IotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 内部（云侧）客户端接入口令：HMAC(internalSecret, username)，username = iot-internal|ts|nonce。
 *
 * 与设备口令分开实现，是因为设备口令要能按 deviceId 定位到单设备密钥，
 * 而内部客户端只有一个共享密钥。把两者混在一个方法里，
 * 迟早会出现"用设备分支去验内部客户端"从而永远失败的调试黑洞。
 *
 * nonce 走 Redis 一次性消费，窗口与设备侧一致。
 */
@Slf4j
@RequiredArgsConstructor
public class InternalClientSecrets {

    private static final String NONCE_KEY_PREFIX = "buddy:iot:internal-nonce:";

    private final StringRedisTemplate redisTemplate;
    private final IotProperties properties;

    public String username(String nonce) {
        return "iot-internal|" + System.currentTimeMillis() + "|" + nonce;
    }

    public String password(String username) {
        return CryptoUtil.hmacSha256Hex(requireSecret(), username);
    }

    /** 校验内部客户端口令；内部口令只在本进程内比较，不查库。 */
    public boolean verify(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        String[] parts = username.split("\\|");
        if (parts.length != 3 || !"iot-internal".equals(parts[0])) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(System.currentTimeMillis() - ts) > properties.getAuthClockWindowSeconds() * 1000L) {
            return false;
        }
        if (!claimNonce(parts[2])) {
            return false;
        }
        return CryptoUtil.equalsConstantTime(password(username), password);
    }

    private String requireSecret() {
        String secret = properties.getInternalSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("buddy.iot.internal-secret 未配置，云侧无法接入自身 Broker");
        }
        return secret;
    }

    private boolean claimNonce(String nonce) {
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(NONCE_KEY_PREFIX + nonce, "1",
                    Duration.ofSeconds(properties.getAuthClockWindowSeconds() * 2L));
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException e) {
            log.error("内部客户端 nonce 校验依赖的 Redis 不可用，按拒绝处理：{}", e.getMessage());
            return false;
        }
    }
}
