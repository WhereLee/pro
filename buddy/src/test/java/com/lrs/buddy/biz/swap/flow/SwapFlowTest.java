package com.lrs.buddy.biz.swap.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrs.buddy.biz.swap.order.OrderState;
import com.lrs.buddy.biz.swap.provision.DeviceProvisionService;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import com.lrs.buddy.biz.swap.service.SwapLedgerService;
import com.lrs.buddy.biz.swap.service.SwapOrderService;
import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.envelope.Envelope;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.security.DeviceSecrets;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 换电主链路（B4）：建单 → S1 下发 → 物理事件推进步骤与订单 → 结算与归属变更。
 *
 * S1 走的是**真 MQTT**：测试里柜机客户端真连上嵌入式 Broker，指令才允许推进订单。
 * 这不是形式主义 —— "startReturn 之后订单在 RETURNING" 这句话的可信度，
 * 取决于"指令真的交到了设备上"；设备没连上却把订单往前推，后面每一步都在假事实上推导。
 *
 * 后续物理事件（door_open / battery_detected / door_close / battery_taken）直接调用流程服务注入：
 * 传输层与校验链的端到端行为已由 M1 的 IotTransportTest / IotCommandTest 证明，
 * 本用例聚焦"事实如何变成状态"，不重复测管道。
 */
@SpringBootTest(properties = {
        "buddy.iot.enabled=true",
        "buddy.iot.port=18886",
        "buddy.iot.host=127.0.0.1",
        "buddy.iot.node-id=flow-test-node"
})
@ActiveProfiles("test")
class SwapFlowTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String BATTERY_PRODUCT = "BAT-60V20AH";
    private static final String MASTER_SECRET = "flow-test-device-secret";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private SwapFlowService flow;
    @Autowired
    private SwapOrderService orders;
    @Autowired
    private SwapLedgerService ledger;
    @Autowired
    private DeviceProvisionService provision;
    @Autowired
    private SwapOrderRepository repo;
    @Autowired
    private DeviceDirectoryDao deviceDao;
    @Autowired
    private DeviceSecrets secrets;
    @Autowired
    private IotProperties properties;
    @Autowired
    private JdbcTemplate jdbc;

    private long member;
    private String cabinetNo;
    private String deviceId;
    private long deviceRowId;
    private Mqtt5BlockingClient device;

    @BeforeEach
    void setUp() {
        member = seedMember(60, 0);
        long stamp = System.nanoTime();
        deviceId = "CABO-FLOW-" + stamp;
        cabinetNo = "CABNO-FLOW-" + stamp;
        var credential = provision.register(PRODUCT_KEY, deviceId, "流程测试柜");
        deviceRowId = credential.deviceRowId();
        // 覆盖成用例已知的主密钥，便于构造可信事件（注册时是随机密钥）
        jdbc.update("UPDATE iot_device SET secret_cipher = ?, online_state = 'ONLINE', last_seen_ts = ? WHERE id = ?",
                com.lrs.buddy.framework.common.util.CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(),
                        MASTER_SECRET), System.currentTimeMillis(), deviceRowId);
        ledger.createCabinet(1L, PRODUCT_KEY, cabinetNo, deviceId, 8, null, null);
    }

    @AfterEach
    void tearDown() {
        if (device != null) {
            device.disconnect();
            device = null;
        }
    }

    @Test
    @DisplayName("全主线走通：AUTHORIZED → RETURNING → RETURNED → VERIFYING → OFFERING → TAKEN → SETTLING → COMPLETED")
    void fullMainLineCompletesAndMovesAssets() throws Exception {
        connectDevice();
        stockBatteries("92", "88", "95");
        String oldBattery = heldBattery("30");   // 用户手上那块旧电池

        SwapOrderService.CreateResult created = orders.create(member, cabinetNo, "H5", "trace-flow");
        assertThat(created.state()).isEqualTo(OrderState.AUTHORIZED);
        flow.startReturn(created.orderId());
        assertThat(state(created.orderId())).isEqualTo("RETURNING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iot_command WHERE biz_id = ? AND cmd_code = 'OPEN_SLOT'",
                Integer.class, created.orderId())).as("S1 必须真的下发过").isEqualTo(1);

        emit(created, "door_open", created.returnSlotNo(), null);
        emit(created, "battery_detected", created.returnSlotNo(), oldBattery);
        emit(created, "door_close", created.returnSlotNo(), oldBattery);
        assertThat(state(created.orderId())).as("门关 + 有投入事实 → 核验通过后进入取电阶段")
                .isEqualTo("OFFERING");
        assertThat(stepState(created.orderId(), 2)).isEqualTo("PHYSICS_DONE");
        assertThat(stepState(created.orderId(), 3)).isEqualTo("VERIFIED");

        emit(created, "door_open", created.offerSlotNo(), null);
        String newBattery = batteryCode(orderRow(created.orderId()).offerBatteryId());
        emit(created, "battery_taken", created.offerSlotNo(), newBattery);
        emit(created, "door_close", created.offerSlotNo(), newBattery);

        assertThat(state(created.orderId())).isEqualTo("COMPLETED");
        assertThat(rightState(created.orderId())).isEqualTo("DEDUCTED");
        Map<String, Object> account = jdbc.queryForMap("SELECT times_total, times_used, times_occupied "
                + "FROM swap_right_account WHERE member_id = ?", member);
        assertThat(((Number) account.get("times_used")).intValue()).isEqualTo(1);
        assertThat(((Number) account.get("times_occupied")).intValue())
                .as("扣减后预占必须归零，否则用户额度被永久占住").isZero();

        Map<String, Object> returned = jdbc.queryForMap("SELECT battery_state, current_slot_id, holder_user_id "
                + "FROM swap_battery WHERE battery_code = ?", oldBattery);
        assertThat(returned.get("battery_state")).isEqualTo("IN_CABINET_CHARGING");
        assertThat(returned.get("holder_user_id")).isNull();
        Map<String, Object> taken = jdbc.queryForMap("SELECT battery_state, holder_user_id, current_slot_id "
                + "FROM swap_battery WHERE id = ?", orderRow(created.orderId()).offerBatteryId());
        assertThat(taken.get("battery_state")).isEqualTo("HELD_BY_USER");
        assertThat(((Number) taken.get("holder_user_id")).longValue()).isEqualTo(member);
        assertThat(taken.get("current_slot_id")).as("新电池已离开柜机").isNull();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot_reservation WHERE order_id = ? "
                        + "AND resv_state = 'ACTIVE'", Integer.class, created.orderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot WHERE slot_state = 'RESERVED_ORDER' "
                + "AND reserved_order_id = ?", Integer.class, created.orderId()))
                .as("订单已终，仓态不能留在 RESERVED_ORDER").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_battery_binding WHERE user_id = ? "
                + "AND bind_state = 'ACTIVE'", Integer.class, member)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_discrepancy WHERE order_id = ?",
                Integer.class, created.orderId())).isZero();
        assertThat(jdbc.queryForList("SELECT seq_no FROM swap_order_event WHERE order_id = ? ORDER BY seq_no",
                Integer.class, created.orderId()))
                .as("事件流是状态的可重放来源，必须单调且完整")
                .isSorted().hasSizeGreaterThanOrEqualTo(9);
    }

    @Test
    @DisplayName("柜机不可达时 startReturn 拒绝，订单停在 AUTHORIZED 且不留永远发不出的指令")
    void unreachableDeviceRefusesToStart() {
        stockBatteries("92", "88");
        SwapOrderService.CreateResult created = orders.create(member, cabinetNo, "H5", "trace-offline");

        assertThatThrownBy(() -> flow.startReturn(created.orderId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state(created.orderId())).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iot_command WHERE biz_id = ? AND biz_type = 'SWAP_ORDER'",
                Integer.class, created.orderId()))
                .as("同事务回滚：不留一条永远发不出去的指令").isZero();
    }

    @Test
    @DisplayName("重复事件不产生第二次迁移（I6）")
    void duplicatedEventDoesNotReapply() throws Exception {
        connectDevice();
        stockBatteries("95");
        SwapOrderService.CreateResult created = orders.create(member, cabinetNo, "H5", "trace-dup");
        flow.startReturn(created.orderId());

        Envelope doorOpen = event("msg-dup", "door_open", created.returnSlotNo(), null);
        emitRaw(doorOpen, created);
        String afterFirst = stepState(created.orderId(), 1);
        emitRaw(doorOpen, created);
        String afterSecond = stepState(created.orderId(), 1);

        assertThat(afterFirst).as("step1 应为 OPEN_CONFIRMED；诊断：unmatched差异=%d, 订单态=%s, 事件幂等行=%d",
                unmatchedCount(), state(created.orderId()), dedupCount(created.orderId())).isEqualTo("OPEN_CONFIRMED");
        assertThat(afterSecond).isEqualTo(afterFirst);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_order_event WHERE order_id = ? "
                        + "AND event_type LIKE 'door_open%'", Integer.class, created.orderId()))
                .as("重复投递不得再写事件").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_event_dedup WHERE order_id = ? AND msg_id = ?",
                Integer.class, created.orderId(), doorOpen.msgId())).isEqualTo(1);
    }

    @Test
    @DisplayName("取走的电池与云端分配的不是同一块：记 IDENTITY_SUSPECT 且不推进")
    void wrongBatteryTakenIsFlagged() throws Exception {
        connectDevice();
        stockBatteries("95", "90");
        SwapOrderService.CreateResult created = orders.create(member, cabinetNo, "H5", "trace-wrong");
        flow.startReturn(created.orderId());
        emit(created, "door_open", created.returnSlotNo(), null);
        emit(created, "battery_detected", created.returnSlotNo(), heldBattery("28"));
        emit(created, "door_close", created.returnSlotNo(), null);
        assertThat(state(created.orderId())).isEqualTo("OFFERING");

        emit(created, "door_open", created.offerSlotNo(), null);
        emit(created, "battery_taken", created.offerSlotNo(), "BAT-NOT-ASSIGNED");

        assertThat(state(created.orderId())).as("比对不上时不能当作正常取走").isEqualTo("OFFERING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_discrepancy WHERE order_id = ? AND kind = "
                        + "'IDENTITY_SUSPECT'", Integer.class, created.orderId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("门关得比投入早：两个事实未齐就不推进到 RETURNED")
    void doorCloseWithoutInsertFactDoesNotAdvance() throws Exception {
        connectDevice();
        stockBatteries("95");
        SwapOrderService.CreateResult created = orders.create(member, cabinetNo, "H5", "trace-order");
        flow.startReturn(created.orderId());
        emit(created, "door_open", created.returnSlotNo(), null);

        emit(created, "door_close", created.returnSlotNo(), null);

        assertThat(state(created.orderId())).as("开了又关、什么都没投，不能算归还完成").isEqualTo("RETURNING");
    }

    @Test
    @DisplayName("柜侧 swap_result 与云端事实不一致时记差异，不改云端结论")
    void swapResultMismatchIsRecorded() throws Exception {
        connectDevice();
        stockBatteries("95", "88");
        SwapOrderService.CreateResult created = orders.create(member, cabinetNo, "H5", "trace-result");
        flow.startReturn(created.orderId());
        emit(created, "door_open", created.returnSlotNo(), null);
        emit(created, "battery_detected", created.returnSlotNo(), heldBattery("25"));
        emit(created, "door_close", created.returnSlotNo(), null);
        emit(created, "door_open", created.offerSlotNo(), null);
        emit(created, "battery_taken", created.offerSlotNo(),
                batteryCode(orderRow(created.orderId()).offerBatteryId()));
        emit(created, "door_close", created.offerSlotNo(), null);
        assertThat(state(created.orderId())).isEqualTo("COMPLETED");

        emitRaw(resultEvent(created, "BAT-CAB-SAYS-OTHER"), created);

        assertThat(state(created.orderId())).as("柜侧陈述不能把已完成的订单改回去").isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_discrepancy WHERE kind = 'SWAP_RESULT_MISMATCH'",
                Integer.class)).as("不一致必须留痕，交对账消化").isEqualTo(1);
    }

    // ---------------- 夹具 ----------------

    private void connectDevice() {
        String username = deviceId + "|" + System.currentTimeMillis() + "|" + System.nanoTime();
        String password = secrets.connectionPassword(MASTER_SECRET, username);
        device = MqttClient.builder().useMqttVersion5()
                .identifier(PRODUCT_KEY + "::" + deviceId)
                .serverHost("127.0.0.1").serverPort(properties.getPort())
                .simpleAuth().username(username)
                .password(ByteBuffer.wrap(password.getBytes(StandardCharsets.UTF_8)))
                .applySimpleAuth().buildBlocking();
        device.connectWith().cleanStart(true).keepAlive(60).send();
        device.subscribeWith().topicFilter("swap/v1/dn/" + PRODUCT_KEY + "/" + deviceId + "/#")
                .qos(MqttQos.AT_LEAST_ONCE).send();
    }

    /** 柜侧回结果：带 orderNo，因为此时订单已 COMPLETED（不再是"在途"），只能靠单号归属。 */
    private Envelope resultEvent(SwapOrderService.CreateResult created, String batterySays) {
        Envelope base = event("msg-result", "swap_result", created.offerSlotNo(), batterySays);
        ObjectNode data = (ObjectNode) base.data();
        data.put("orderNo", created.orderNo());
        return base;
    }

    private Envelope event(String msgId, String eventType, Integer slotNo, String batteryCode) {
        long now = System.currentTimeMillis();
        ObjectNode data = JSON.createObjectNode().put("eventType", eventType);
        if (slotNo != null) {
            data.put("slotNo", slotNo);
        }
        if (batteryCode != null) {
            data.put("batteryCode", batteryCode);
        }
        return new Envelope("1.0", msgId + "-" + SEQ.incrementAndGet(), now, now + 60_000L,
                "nonce-" + SEQ.incrementAndGet(), null, "s-flow", PRODUCT_KEY + "::" + deviceId, null,
                SEQ.get(), null, null, data, null);
    }

    private void emit(SwapOrderService.CreateResult created, String eventType, Integer slotNo, String batteryCode) {
        emitRaw(event("msg-" + eventType, eventType, slotNo, batteryCode), created);
    }

    private void emitRaw(Envelope envelope, SwapOrderService.CreateResult created) {
        flow.onEvent(deviceDao.findDeviceById(deviceRowId), envelope);
    }

    private void stockBatteries(String... socs) {
        int slotNo = 1;
        for (String soc : socs) {
            ledger.registerBattery(cabinetNo, slotNo++, "BAT-F" + System.nanoTime() + "-" + slotNo,
                    BATTERY_PRODUCT, Integer.parseInt(soc), new BigDecimal("26.0"),
                    new BigDecimal("20.0"), new BigDecimal("60.0"));
        }
    }

    /** 造一块"在用户手上"的电池（HELD_BY_USER、不在任何仓内），返回其电池码。 */
    private String heldBattery(String soc) {
        String code = "BAT-OLD-" + System.nanoTime();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO swap_battery (id, battery_code, product_key, battery_state, own_type, "
                        + "holder_user_id, location_state, soc, soh, cycle_count, create_time, update_time, version, "
                        + "del_flag, tenant_id) VALUES (?,?,?, 'HELD_BY_USER', 'USER_OWNED', ?, 'KNOWN', ?, 98, 12, ?,?, 0, 0, 1)",
                SEQ.getAndAdd(1000) + 5_000_000L, code, BATTERY_PRODUCT, member, Integer.parseInt(soc), now, now);
        return code;
    }

    private String batteryCode(Long batteryId) {
        return batteryId == null ? null
                : jdbc.queryForObject("SELECT battery_code FROM swap_battery WHERE id = ?", String.class, batteryId);
    }

    private SwapOrderRepository.OrderRow orderRow(long orderId) {
        return repo.findOrder(orderId);
    }

    private int unmatchedCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM swap_discrepancy WHERE kind = 'UNMATCHED_EVENT'",
                Integer.class);
    }

    private int dedupCount(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM swap_event_dedup WHERE order_id = ?", Integer.class, orderId);
    }

    private String state(long orderId) {
        return orderRow(orderId).state();
    }

    private String rightState(long orderId) {
        return orderRow(orderId).rightState();
    }

    private String stepState(long orderId, int stepNo) {
        Object value = repo.step(orderId, stepNo).get("step_state");
        return value == null ? null : value.toString();
    }

    private long seedMember(int timesTotal, int timesUsed) {
        long id = 950_000L + SEQ.incrementAndGet();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO member_user (id, member_no, nickname, realname_state, member_state, "
                        + "register_source, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'VERIFIED', 'NORMAL', 'H5', ?,?, 0, 0, 1)",
                id, "M" + id, "流程测试会员", now, now);
        jdbc.update("INSERT INTO swap_right_account (id, member_id, plan_id, times_total, times_used, times_occupied, "
                        + "valid_from, valid_until, freeze_state, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?, ?, 1, ?, ?, 0, ?, '2099-12-31 00:00:00', 'NORMAL', ?, ?, 0, 0, 1)",
                id * 10, id, timesTotal, timesUsed, now, now, now);
        return id;
    }
}
