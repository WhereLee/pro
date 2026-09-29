package com.lrs.buddy.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck;
import com.lrs.buddy.framework.common.util.CryptoUtil;
import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.envelope.Envelope;
import com.lrs.buddy.framework.iot.envelope.JsonPayloadCodec;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.security.DeviceSecrets;
import com.lrs.buddy.framework.iot.transport.InboundRouter;
import com.lrs.buddy.framework.iot.transport.MqttTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1 块 1 的自证：设备能连上、认证与 ACL 生效、上行报文走完校验链并被分发、重复与伪造被挡住。
 *
 * 用真实 TCP + 真实 Broker + 真实 H2，不 mock 任何一环 ——
 * 这一层的全部价值就在于"报文真的走完了一次"，mock 掉等于没测。
 *
 * 端口固定 18884，避开开发机上可能存在的 1883；只在测试上下文内启动。
 *
 * 【为何暂时 @Disabled】本测试跑到了真 Broker 的 CONNECT 阶段就发现：
 * Moquette 0.17 对 MQTT 5 CONNECT 回的 CONNACK 无法被标准 v5 客户端解码（MqttDecodeException:
 * "Exception while decoding CONNACK: wrong reason code"），因此无法作为本项目的本地 Broker。
 * 这不是测试写错，而是选型结论：接入层换成 Vert.x MQTT Server（同为纯 Java、可嵌入、Apache-2.0），
 * 完成后去掉本注解。开关默认 false 只为了开发/CI 可启动，不等于该问题已解决。
 */
@Disabled("Moquette 0.17 与 MQTT5 客户端在 CONNACK 上不兼容；待接入层换为 Vert.x MQTT Server 后启用")
@SpringBootTest(properties = {
        "buddy.iot.enabled=true",
        "buddy.iot.port=18884",
        "buddy.iot.host=127.0.0.1",
        "buddy.iot.node-id=test-node"
})
@ActiveProfiles("test")
@Import(IotTransportTest.TestListenerConfig.class)
class IotTransportTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String DEVICE_ID = "CAB-TEST-0001";
    private static final String OTHER_DEVICE_ID = "CAB-TEST-0002";
    private static final String MASTER_SECRET = "unit-test-device-master-secret";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DeviceSecrets deviceSecrets;
    @Autowired
    private JsonPayloadCodec codec;
    @Autowired
    private IotProperties properties;
    @Autowired
    private CapturedListener listener;

    /** 测试用监听器：验证"只有校验链全通才分发"这条边界。 */
    static class CapturedListener implements InboundRouter.InboundListener {

        final List<Envelope> received = new CopyOnWriteArrayList<>();
        volatile CountDownLatch latch = new CountDownLatch(1);

        void reset(int permits) {
            received.clear();
            latch = new CountDownLatch(permits);
        }

        @Override
        public boolean supports(MqttTopics.Kind kind) {
            return kind == MqttTopics.Kind.EVENT;
        }

        @Override
        public void onInbound(MqttTopics.Inbound topic, Envelope envelope, DeviceDirectoryDao.Device device) {
            received.add(envelope);
            latch.countDown();
        }
    }

    @TestConfiguration
    static class TestListenerConfig {

        @Bean
        CapturedListener capturedListener() {
            return new CapturedListener();
        }
    }

    private long ensureDevice(String deviceId) {
        Long existing = findDeviceId(deviceId);
        if (existing != null) {
            return existing;
        }
        long id = System.nanoTime();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("""
                INSERT INTO iot_device (id, product_key, device_id, device_name, secret_cipher, secret_version,
                        online_state, enabled, create_time, update_time, version, del_flag, tenant_id)
                VALUES (?,?,?,?,?,1,'UNKNOWN',1,?,?,0,0,1)
                """, id, PRODUCT_KEY, deviceId, deviceId,
                CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), MASTER_SECRET), now, now, 1L);
        return id;
    }

    private Long findDeviceId(String deviceId) {
        try {
            return jdbc.queryForObject("SELECT id FROM iot_device WHERE device_id = ?", Long.class, deviceId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private Mqtt5BlockingClient deviceClient(String deviceId, String username, String password) {
        return MqttClient.builder()
                .useMqttVersion5()
                .identifier(PRODUCT_KEY + "::" + deviceId)
                .serverHost("127.0.0.1")
                .serverPort(properties.getPort())
                .simpleAuth()
                .username(username)
                .password(ByteBuffer.wrap(password.getBytes(StandardCharsets.UTF_8)))
                .applySimpleAuth()
                .buildBlocking();
    }

    private String username() {
        return DEVICE_ID + "|" + System.currentTimeMillis() + "|nonce-" + System.nanoTime();
    }

    private Envelope signedEnvelope(String masterSecret, String msgId) {
        ObjectMapper mapper = new ObjectMapper();
        var data = mapper.createObjectNode();
        data.put("eventType", "door_open");
        data.put("slotNo", 3);
        Envelope unsigned = new Envelope("1.0", msgId, System.currentTimeMillis(),
                System.currentTimeMillis() + 60_000L, "nonce-" + System.nanoTime(), "trace-1", null,
                PRODUCT_KEY + "::" + DEVICE_ID, null, 1L, null, null, data, null);
        String sign = deviceSecrets.sign(deviceSecrets.deriveMsgSecret(masterSecret), unsigned);
        return new Envelope("1.0", msgId, unsigned.issuedAt(), unsigned.expireAt(), unsigned.nonce(),
                "trace-1", null, unsigned.from(), null, 1L, null, null, data, sign);
    }

    @Test
    @DisplayName("正确口令的设备可以连入，报文走完校验链并被分发、同时留痕")
    void deviceConnectsAndPublishIsRouted() throws Exception {
        long id = ensureDevice(DEVICE_ID);
        String username = username();
        String password = deviceSecrets.connectionPassword(MASTER_SECRET, username);
        listener.reset(1);

        Mqtt5BlockingClient client = deviceClient(DEVICE_ID, username, password);
        Mqtt5ConnAck ack = client.connectWith().cleanStart(true).keepAlive(60).send();
        assertThat(ack.getReasonCode().name()).isEqualTo("SUCCESS");

        client.publishWith()
                .topic("swap/v1/up/" + PRODUCT_KEY + "/" + DEVICE_ID + "/event")
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload(codec.encode(signedEnvelope(MASTER_SECRET, "msg-connect-1")))
                .send();

        assertThat(listener.latch.await(8, TimeUnit.SECONDS)).as("校验链未通过或分发丢失").isTrue();
        assertThat(listener.received).hasSize(1);
        assertThat(listener.received.get(0).msgId()).isEqualTo("msg-connect-1");

        Integer logged = jdbc.queryForObject(
                "SELECT COUNT(*) FROM iot_message_log WHERE device_row_id = ? AND valid_flag = 1",
                Integer.class, id);
        assertThat(logged).as("留痕必须与分发同时发生，否则事后无法复核").isPositive();
        client.disconnect();
    }

    @Test
    @DisplayName("错误口令被拒绝连接")
    void wrongPasswordRejected() {
        ensureDevice(DEVICE_ID);
        Mqtt5BlockingClient client = deviceClient(DEVICE_ID, username(), "deadbeef");
        assertThatThrownBy(() -> client.connectWith().cleanStart(true).send())
                .as("匿名与错口令都必须挡住，否则知道 deviceId 就能伪造上报")
                .isInstanceOf(Mqtt5ConnAckException.class);
    }

    @Test
    @DisplayName("同一 msgId 重复投递只分发一次")
    void duplicateMessageDeliveredOnce() throws Exception {
        long id = ensureDevice(DEVICE_ID);
        String username = username();
        String password = deviceSecrets.connectionPassword(MASTER_SECRET, username);
        listener.reset(1);

        Mqtt5BlockingClient client = deviceClient(DEVICE_ID, username, password);
        client.connectWith().cleanStart(true).keepAlive(60).send();

        byte[] payload = codec.encode(signedEnvelope(MASTER_SECRET, "msg-dup-1"));
        String topic = "swap/v1/up/" + PRODUCT_KEY + "/" + DEVICE_ID + "/event";
        client.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).payload(payload).send();
        client.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).payload(payload).send();

        assertThat(listener.latch.await(8, TimeUnit.SECONDS)).isTrue();
        // 再等一段，确保第二次是被挡住而不是"还没到"
        TimeUnit.SECONDS.sleep(2);
        assertThat(listener.received).as("重复上行必须走去重分支").hasSize(1);

        Integer deduped = jdbc.queryForObject(
                "SELECT COUNT(*) FROM iot_msg_dedup WHERE device_row_id = ? AND msg_id = ?",
                Integer.class, id, "msg-dup-1");
        assertThat(deduped).isEqualTo(1);
        client.disconnect();
    }

    @Test
    @DisplayName("签名不符的上行不分发，并留下 E1001 拒绝码")
    void forgedSignatureIsRejected() throws Exception {
        ensureDevice(DEVICE_ID);
        String username = username();
        String password = deviceSecrets.connectionPassword(MASTER_SECRET, username);
        listener.reset(1);

        Mqtt5BlockingClient client = deviceClient(DEVICE_ID, username, password);
        client.connectWith().cleanStart(true).keepAlive(60).send();

        // 用另一个密钥签名：等价于攻击者拿到 deviceId 但没有主密钥
        client.publishWith()
                .topic("swap/v1/up/" + PRODUCT_KEY + "/" + DEVICE_ID + "/event")
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload(codec.encode(signedEnvelope("attacker-guessed-secret", "msg-forged-1")))
                .send();

        assertThat(listener.latch.await(3, TimeUnit.SECONDS)).as("伪造签名不得进入分发").isFalse();
        Integer rejected = jdbc.queryForObject(
                "SELECT COUNT(*) FROM iot_message_log WHERE reject_code = 'E1001' AND valid_flag = 0",
                Integer.class);
        assertThat(rejected).as("拒绝必须留痕且带步骤，否则排障说不清谁拒的").isPositive();
        client.disconnect();
    }

    @Test
    @DisplayName("设备订阅他人主题被 ACL 拒绝")
    void crossDeviceSubscribeDenied() throws Exception {
        ensureDevice(DEVICE_ID);
        ensureDevice(OTHER_DEVICE_ID);
        String username = username();
        String password = deviceSecrets.connectionPassword(MASTER_SECRET, username);

        Mqtt5BlockingClient client = deviceClient(DEVICE_ID, username, password);
        client.connectWith().cleanStart(true).keepAlive(60).send();

        var subAck = client.subscribeWith()
                .topicFilter("swap/v1/dn/" + PRODUCT_KEY + "/" + OTHER_DEVICE_ID + "/cmd")
                .qos(MqttQos.AT_LEAST_ONCE)
                .send();
        assertThat(subAck.getReasonCodes())
                .as("订阅别人的下行主题等于偷看别人的开仓指令，必须拒绝")
                .allMatch(code -> !code.name().startsWith("GRANTED"));
        client.disconnect();
    }
}
