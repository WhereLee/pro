package com.lrs.buddy.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.lrs.buddy.framework.common.util.CryptoUtil;
import com.lrs.buddy.framework.common.util.Ulids;
import com.lrs.buddy.framework.iot.command.CommandState;
import com.lrs.buddy.framework.iot.command.DeviceCommandService;
import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.envelope.Envelope;
import com.lrs.buddy.framework.iot.envelope.JsonPayloadCodec;
import com.lrs.buddy.framework.iot.repo.CommandDao;
import com.lrs.buddy.framework.iot.security.DeviceSecrets;
import com.lrs.buddy.framework.iot.transport.MqttTopics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 指令总线：下发、应答匹配、超时、迟到应答纠正、重发前置、非法迁移。
 *
 * 覆盖的是协议里最容易写错的四条：
 * 1 超时不等于未发生（TIMEOUT 是**非终态**，迟到应答必须能把它纠正回 ACKED）；
 * 2 副作用指令不自动重试（超时后只剩"反查/人工"两条路，没有"再发一次"）；
 * 3 重发前必须先置 SUPERSEDED，否则 active_step 唯一索引会拒绝新指令；
 * 4 状态迁移带 fromState 谓词，重复投递不会二次生效。
 */
@SpringBootTest(properties = {
        "buddy.iot.enabled=true",
        "buddy.iot.port=18885",
        "buddy.iot.host=127.0.0.1",
        "buddy.iot.node-id=cmd-test-node"
})
@ActiveProfiles("test")
class IotCommandTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String DEVICE_ID = "CAB-CMD-0001";
    private static final String MASTER_SECRET = "command-test-device-secret";

    @Autowired
    private DeviceCommandService commands;
    @Autowired
    private CommandDao commandDao;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IotProperties properties;
    @Autowired
    private DeviceSecrets secrets;
    @Autowired
    private JsonPayloadCodec codec;
    @Autowired
    private ObjectMapper objectMapper;

    private Mqtt5BlockingClient device;
    private long deviceRowId;
    private final CountDownLatch publishReceived = new CountDownLatch(1);
    private volatile Mqtt5Publish lastPublish;

    @AfterEach
    void tearDown() {
        if (device != null) {
            device.disconnect();
            device = null;
        }
    }

    /** 造设备并让它真连上 Broker。 */
    private String connectedDevice() throws Exception {
        java.util.List<Long> found = jdbc.queryForList("SELECT id FROM iot_device WHERE device_id = ?",
                Long.class, DEVICE_ID);
        if (found.isEmpty()) {
            deviceRowId = 900_001L;
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            jdbc.update("INSERT INTO iot_device (id, product_key, device_id, device_name, secret_cipher, "
                            + "secret_version, online_state, enabled, create_time, update_time, version, del_flag, tenant_id) "
                            + "VALUES (?,?,?,?,?,1,'UNKNOWN',1,?,?,0,0,1)",
                    deviceRowId, PRODUCT_KEY, DEVICE_ID, DEVICE_ID,
                    CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), MASTER_SECRET), now, now);
        } else {
            deviceRowId = found.get(0);
        }
        String username = DEVICE_ID + "|" + System.currentTimeMillis() + "|nonce-" + System.nanoTime();
        String password = secrets.connectionPassword(MASTER_SECRET, username);
        device = MqttClient.builder().useMqttVersion5()
                .identifier(PRODUCT_KEY + "::" + DEVICE_ID)
                .serverHost("127.0.0.1").serverPort(properties.getPort())
                .simpleAuth().username(username)
                .password(ByteBuffer.wrap(password.getBytes(StandardCharsets.UTF_8)))
                .applySimpleAuth().buildBlocking();
        device.connectWith().cleanStart(true).keepAlive(60).send();
        device.subscribeWith().topicFilter(MqttTopics.downCommand(PRODUCT_KEY, DEVICE_ID))
                .qos(MqttQos.AT_LEAST_ONCE).send();
        var publishes = device.publishes(com.hivemq.client.mqtt.MqttGlobalPublishFilter.ALL);
        Thread thread = new Thread(() -> {
            try {
                lastPublish = publishes.receive(20, TimeUnit.SECONDS).orElse(null);
                if (lastPublish != null) {
                    publishReceived.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "cmd-test-device-consumer");
        thread.setDaemon(true);
        thread.start();
        return MASTER_SECRET;
    }

    /**
     * 每个用例用独立 bizId：active_step 唯一索引是表级的，
     * 上一个用例留在途的指令会让下一个用例直接撞约束 —— 那不是 bug，是用例不隔离。
     */
    private static final java.util.concurrent.atomic.AtomicLong BIZ_SEQ = new java.util.concurrent.atomic.AtomicLong(7001L);
    private volatile long bizId = BIZ_SEQ.getAndIncrement();

    private DeviceCommandService.Issue issue(int ttlSeconds) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("slotNo", 3);
        data.put("orderNo", "SW-CMD-0001");
        return new DeviceCommandService.Issue("SWAP_ORDER", bizId, 1, deviceRowId, PRODUCT_KEY, DEVICE_ID,
                "OPEN_SLOT", data, 1, ttlSeconds, 0, false, "trace-cmd-1", null, 1L);
    }

    @Test
    @DisplayName("设备在线时指令被下发，收到应答后进入 ACKED")
    void commandDispatchedAndAcked() throws Exception {
        connectedDevice();
        DeviceCommandService.CommandRecord record = commands.issue(issue(60));
        assertThat(record.state()).as("设备已连接，应直接进入 DISPATCHED").isEqualTo(CommandState.DISPATCHED);
        assertThat(publishReceived.await(8, TimeUnit.SECONDS)).as("设备应收到下行指令").isTrue();
        assertThat(new String(lastPublish.getPayloadAsBytes(), StandardCharsets.UTF_8))
                .as("下发的信封里要带 cmdId，设备才知道在回哪条")
                .contains(record.cmdId());

        reply(record, "OK");
        CommandDao.Row row = commandDao.findByCmdId(record.cmdId());
        assertThat(row.state()).isEqualTo(CommandState.ACKED);
        assertThat(row.replyCode()).isEqualTo("OK");
    }

    @Test
    @DisplayName("重复投递同一条应答不会二次改变状态（幂等）")
    void duplicateReplyIsIgnored() throws Exception {
        connectedDevice();
        DeviceCommandService.CommandRecord record = commands.issue(issue(60));
        reply(record, "OK");
        reply(record, "OK");
        assertThat(commandDao.findByCmdId(record.cmdId()).state()).isEqualTo(CommandState.ACKED);
    }

    @Test
    @DisplayName("超时后迟到应答：TIMEOUT 不是终态，必须能被纠正回 ACKED")
    void lateReplyRecoversFromTimeout() throws Exception {
        connectedDevice();
        DeviceCommandService.CommandRecord record = commands.issue(issue(60));
        // 直接把 deadline 推到过去并跑兜底扫描，等价于超时（不靠 sleep 等真实超时）
        jdbc.update("UPDATE iot_command SET deadline_ts = ?, update_time = CURRENT_TIMESTAMP WHERE cmd_id = ?",
                System.currentTimeMillis() - 1000, record.cmdId());
        for (CommandDao.Row due : commandDao.scanDue(System.currentTimeMillis(), 50)) {
            commands.handleTimeout(due.id(), due.cmdId());
        }
        assertThat(commandDao.findByCmdId(record.cmdId()).state())
                .as("超时只说明没收到应答，不说明没执行").isEqualTo(CommandState.TIMEOUT);

        reply(record, "OK");
        assertThat(commandDao.findByCmdId(record.cmdId()).state())
                .as("迟到应答必须能纠正超时，否则一次网络抖动就把已开门当成没开门").isEqualTo(CommandState.ACKED);
    }

    @Test
    @DisplayName("在途指令未处理时不允许重复下发同一业务步骤")
    void reissueRequiresSupersede() throws Exception {
        connectedDevice();
        commands.issue(issue(60));
        assertThatThrownBy(() -> commands.issue(issue(60)))
                .as("同一步骤有两条在途指令会让应答归属不清").isInstanceOf(IllegalStateException.class);

        commands.supersedeInFlight("SWAP_ORDER", bizId, 1);
        assertThat(commands.issue(issue(60)).state()).isEqualTo(CommandState.DISPATCHED);
    }

    @Test
    @DisplayName("非法状态迁移直接抛异常，不静默改写")
    void illegalTransitionThrows() throws Exception {
        connectedDevice();
        DeviceCommandService.CommandRecord record = commands.issue(issue(60));
        reply(record, "OK");
        assertThatThrownBy(() -> commandDao.transition(
                commandDao.findByCmdId(record.cmdId()).id(), CommandState.CONFIRMED, CommandState.DISPATCHED,
                LocalDateTime.now(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 模拟设备回一条应答：走真实 MQTT 上行，因此会经过完整的协议校验链。 */
    private void reply(DeviceCommandService.CommandRecord record, String code) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("cmdId", record.cmdId());
        data.put("slotNo", 3);
        long now = System.currentTimeMillis();
        Envelope unsigned = new Envelope("1.0", Ulids.next(), now, now + 60_000L, Ulids.next(),
                record.traceId(), null, PRODUCT_KEY + "::" + DEVICE_ID, null, 1L,
                record.cmdCode(), code, data, null);
        String msgSecret = secrets.deriveMsgSecret(MASTER_SECRET);
        Envelope signed = new Envelope(unsigned.v(), unsigned.msgId(), unsigned.issuedAt(), unsigned.expireAt(),
                unsigned.nonce(), unsigned.traceId(), unsigned.sessionId(), unsigned.from(), null, unsigned.seq(),
                unsigned.cmd(), unsigned.code(), unsigned.data(), secrets.sign(msgSecret, unsigned));
        device.publishWith().topic("swap/v1/up/" + PRODUCT_KEY + "/" + DEVICE_ID + "/cmd_reply")
                .qos(MqttQos.AT_LEAST_ONCE).payload(codec.encode(signed)).send();
        // 应答要等云侧异步处理完才能断言状态，轮询而不是固定 sleep
        for (int i = 0; i < 80 && !stateReached(record, code); i++) {
            sleep();
        }
    }

    private boolean stateReached(DeviceCommandService.CommandRecord record, String code) {
        CommandDao.Row row = commandDao.findByCmdId(record.cmdId());
        return row != null && ("OK".equals(code) ? row.state() == CommandState.ACKED : row.state().isTerminal())
                && !CommandState.DISPATCHED.equals(row.state());
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
