package com.lrs.buddy.biz.swap;

import com.lrs.buddy.biz.swap.provision.DeviceProvisionService;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.security.DeviceCredentialService;
import com.lrs.buddy.framework.iot.security.DeviceSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 设备开通与凭证颁发（M2-0b）。
 *
 * 最关键的一条断言不是"能注册"，而是**注册拿到的密钥真的能通过接入层认证**：
 * 只测"库里存了密文"没有意义，那仍然证明不了主链路可以在真凭证上跑起来。
 * 所以这里把 register → 派生 connSecret → authenticateConnection 一路打穿。
 */
@SpringBootTest
@ActiveProfiles("test")
class DeviceProvisionTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";

    @Autowired
    private DeviceProvisionService provision;
    @Autowired
    private DeviceCredentialService credentials;
    @Autowired
    private DeviceDirectoryDao deviceDao;
    @Autowired
    private DeviceSecrets secrets;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("注册后：库存密文、明文只在响应里出现一次，且能通过接入层认证")
    void registeredDeviceCanAuthenticateAtBroker() {
        String deviceId = "CAB-PROV-0001";
        cleanup(deviceId);

        DeviceProvisionService.Credential credential = provision.register(PRODUCT_KEY, deviceId, "开通测试柜");

        assertThat(credential.masterSecret()).as("32 字节 Base64").hasSize(44);
        assertThat(credential.deviceRowId()).isNotNull();
        String cipher = jdbc.queryForObject("SELECT secret_cipher FROM iot_device WHERE id = ?",
                String.class, credential.deviceRowId());
        assertThat(cipher).as("库里存的是密文，绝不能等于明文")
                .isNotEqualTo(credential.masterSecret()).isNotBlank();

        DeviceDirectoryDao.Device device = deviceDao.findDeviceById(credential.deviceRowId());
        assertThat(credentials.masterSecretOf(device)).isEqualTo(credential.masterSecret());

        // nonce 必须每次不同：它是一次性消费凭证，写死后同一个测试类跑第二次就会被自判重放
        // （这不是推测：上一版写了 nonce-provision-1，单跑绿、全量跑红，原因就在此）。
        String username = deviceId + "|" + System.currentTimeMillis() + "|" + java.util.UUID.randomUUID();
        String password = secrets.connectionPassword(credential.masterSecret(), username);
        assertThat(credentials.authenticateConnection(PRODUCT_KEY + "::" + deviceId, username,
                password.getBytes(StandardCharsets.UTF_8)))
                .as("注册出来的凭证必须能直接过接入层认证，否则 M2 主链路是假的")
                .isNotNull();
    }

    @Test
    @DisplayName("轮转后旧密钥立刻失效、新密钥可用")
    void rotationInvalidatesOldSecret() {
        String deviceId = "CAB-PROV-0002";
        cleanup(deviceId);
        DeviceProvisionService.Credential first = provision.register(PRODUCT_KEY, deviceId, null);

        DeviceProvisionService.Credential second = provision.rotate(PRODUCT_KEY, deviceId);

        assertThat(second.secretVersion()).isEqualTo(2);
        assertThat(second.masterSecret()).isNotEqualTo(first.masterSecret());
        assertThat(authenticate(deviceId, first.masterSecret()))
                .as("旧密钥必须立即失效：能继续认证就等于轮转没生效").isNull();
        assertThat(authenticate(deviceId, second.masterSecret())).isNotNull();
    }

    @Test
    @DisplayName("重复注册同一 deviceId 被拒，且不会留下第二条台账")
    void duplicateDeviceIdRejected() {
        String deviceId = "CAB-PROV-0003";
        cleanup(deviceId);
        provision.register(PRODUCT_KEY, deviceId, null);

        assertThatThrownBy(() -> provision.register(PRODUCT_KEY, deviceId, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count(deviceId)).isEqualTo(1);
    }

    @Test
    @DisplayName("非法 deviceId 与未知产品分别被挡在参数校验，不落到 DB")
    void invalidArgumentsAreRejectedBeforeDb() {
        assertThatThrownBy(() -> provision.register(PRODUCT_KEY, "bad id/with slash", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provision.register("SWAP-NOT-EXIST", "CAB-PROV-0004", null))
                .as("产品不存在就不能发凭证，否则会造出永远连不上又占着唯一索引的幽灵设备")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("CAB-PROV-0004")).isZero();
    }

    /**
     * “可用设备集合里不得有解不开的密文”。
     *
     * M0-3 的种子曾留下一台 secret_cipher='DEMO-CIPHER-REPLACE-IN-DEV' 的样例柜机，
     * 台账看上去正常，实际永远认证失败；V15 已停用。本断言把这个坑固化成门禁：
     * 以后任何“先填个占位串、回头再改”的种子都会在这里红，而不是等到演示时变成“柜机离线”。
     */
    @Test
    @DisplayName("enabled=1 的设备绝不允许携带占位密文")
    void noPlaceholderCipherAmongEnabledDevices() {
        Integer bad = jdbc.queryForObject("SELECT COUNT(*) FROM iot_device WHERE enabled = 1 "
                + "AND (secret_cipher IS NULL OR LENGTH(secret_cipher) < 24 "
                + "OR secret_cipher LIKE 'DEMO-CIPHER%')", Integer.class);
        assertThat(bad == null ? 0 : bad)
                .as("占位密文必须处于 enabled=0，否则就是个永远连不上的幽灵设备").isZero();
    }

    private DeviceDirectoryDao.Device authenticate(String deviceId, String masterSecret) {
        String username = deviceId + "|" + System.currentTimeMillis() + "|nonce-" + System.nanoTime();
        String password = secrets.connectionPassword(masterSecret, username);
        return credentials.authenticateConnection(PRODUCT_KEY + "::" + deviceId, username,
                password.getBytes(StandardCharsets.UTF_8));
    }

    private int count(String deviceId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM iot_device WHERE product_key = ? "
                + "AND device_id = ?", Integer.class, PRODUCT_KEY, deviceId);
        return count == null ? 0 : count;
    }

    /** 用例自带清理：H2 在整个测试 JVM 内共享，注册类用例天然幂等要求高。 */
    private void cleanup(String deviceId) {
        jdbc.update("DELETE FROM iot_device WHERE product_key = ? AND device_id = ?", PRODUCT_KEY, deviceId);
    }
}
