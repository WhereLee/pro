package com.lrs.buddy.biz.swap.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrs.buddy.biz.swap.provision.DeviceProvisionService;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import com.lrs.buddy.biz.swap.service.SwapLedgerService;
import com.lrs.buddy.biz.swap.service.SwapOrderService;
import com.lrs.buddy.framework.common.util.CryptoUtil;
import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.envelope.Envelope;
import com.lrs.buddy.framework.iot.envelope.JsonPayloadCodec;
import com.lrs.buddy.framework.iot.security.DeviceSecrets;
import com.lrs.buddy.framework.iot.transport.InboundRouter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FI 的云侧效果（M3 阶段 2）：报文从 {@link InboundRouter} 进，断言**四个维度同时成立**。
 *
 * 为什么坚持走 router 而不是直接调流程服务：直调等于跳过验签、有效期、会话盖章、去重四道门，
 * 而 FI-02/05/06/14 的形状恰恰发生在这四道门与流程的接缝上。
 * 跨进程那一端（真 buddy + 真 buddy-sim）由 {@code scripts/cross-process-swap.sh} 在 CI 里守主线，
 * 这里守的是**故障分支的云侧落点**——两者不重复。
 *
 * 每条都写满四行断言，顺序固定：**状态 / 资产 / 权益 / 工单与差异**。
 * 少任何一行，这条用例就退化成"没抛异常"。
 */
@SpringBootTest
@ActiveProfiles("test")
class SwapFiCloudEffectsTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String BATTERY_PRODUCT = "BAT-60V20AH";
    private static final AtomicLong SEQ = new AtomicLong();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private InboundRouter router;
    @Autowired
    private JsonPayloadCodec codec;
    @Autowired
    private DeviceSecrets deviceSecrets;
    @Autowired
    private MeterRegistry registry;
    @Autowired
    private SwapOrderRepository repo;
    @Autowired
    private SwapOrderService orders;
    @Autowired
    private SwapLedgerService ledger;
    @Autowired
    private DeviceProvisionService provision;
    @Autowired
    private IotProperties properties;
    @Autowired
    private JdbcTemplate jdbc;

    private String cabinetNo;
    private String deviceId;
    private long deviceRowId;
    private long member;

    @BeforeEach
    void setUp() {
        long stamp = System.nanoTime();
        cabinetNo = "CAB-FI-" + stamp;
        deviceId = "CABO-FI-" + stamp;
        member = seedMember();
        var credential = provision.register(PRODUCT_KEY, deviceId, "云侧 FI 柜");
        deviceRowId = credential.deviceRowId();
        jdbc.update("UPDATE iot_device SET secret_cipher = ?, online_state = 'ONLINE' WHERE id = ?",
                CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), masterSecret()), deviceRowId);
        ledger.createCabinet(1L, PRODUCT_KEY, cabinetNo, deviceId, 8, null, null);
        ledger.registerBattery(cabinetNo, 1, "BAT-FI-" + stamp, BATTERY_PRODUCT, 92,
                new BigDecimal("26.0"), new BigDecimal("20.0"), new BigDecimal("60.0"));
    }

    @Test
    @DisplayName("FI-02 同 msgId 重复投递：只推进一次，四个维度都不叠加")
    void duplicateEventIsNotAppliedTwice() {
        long orderId = startedOrder();
        int returnSlot = returnSlotOf(orderId);
        byte[] payload = codec.encode(event("door_open", returnSlot, null, "FI02-" + SEQ.incrementAndGet()));
        String clientId = PRODUCT_KEY + "::" + deviceId;
        String topic = "swap/v1/up/" + PRODUCT_KEY + "/" + deviceId + "/event";

        router.dispatch(topic, payload, clientId, 1);
        awaitStep(orderId, 1, "OPEN_CONFIRMED");
        router.dispatch(topic, payload, clientId, 1);   // 同一条报文再来一次
        settle();

        // 状态
        assertThat(orderState(orderId)).as("重复投递不能把订单推到下一个态").isEqualTo("RETURNING");
        assertThat(stepState(orderId, 1)).isEqualTo("OPEN_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_order_event WHERE order_id = ? "
                + "AND event_type = 'door_open@return'", Integer.class, orderId))
                .as("事实事件表也只允许一条").isEqualTo(1);
        // 资产
        assertThat(jdbc.queryForObject("SELECT door_state FROM swap_slot WHERE cabinet_id = ? AND slot_no = ?",
                String.class, cabinetId(orderId), returnSlot)).isEqualTo("OPEN");
        // 权益
        assertThat(rightState(orderId)).as("门开一次都不该动权益，更别说两次").isEqualTo("OCCUPIED");
        // 工单与差异
        assertThat(discrepancies(orderId)).as("幂等丢弃不是差异").isZero();
    }

    @Test
    @DisplayName("FI-05 事件乱序（door_close 先到）：不许凭空承认事实，后续正序事件仍能推进")
    void outOfOrderDoorCloseDoesNotAdvanceState() {
        long orderId = startedOrder();
        int returnSlot = returnSlotOf(orderId);
        String clientId = PRODUCT_KEY + "::" + deviceId;
        String topic = "swap/v1/up/" + PRODUCT_KEY + "/" + deviceId + "/event";

        router.dispatch(topic, codec.encode(event("door_close", returnSlot, null,
                "FI05C-" + SEQ.incrementAndGet())), clientId, 1);
        settle();

        // 状态：没有 door_open 在前，关门不能承认"投好了"
        assertThat(orderState(orderId)).isEqualTo("RETURNING");
        assertThat(stepState(orderId, 1)).as("S1 不能被一条倒挂的关门推进").isNotEqualTo("OPEN_CONFIRMED");
        assertThat(stepState(orderId, 2)).isNotEqualTo("PHYSICS_DONE");
        // 资产
        assertThat(activeBindings()).as("乱序事件不得改归属").isZero();
        // 权益
        assertThat(rightState(orderId)).isEqualTo("OCCUPIED");
        // 工单与差异：云侧"知道门要开却没收到开门"不该被伪装成账实差异（那是设备侧漏报才该记的）
        assertThat(discrepancies(orderId)).isZero();

        // 正序到达后必须能继续走：倒挂不能把单子永久毒化
        router.dispatch(topic, codec.encode(event("door_open", returnSlot, null,
                "FI05O-" + SEQ.incrementAndGet())), clientId, 1);
        awaitStep(orderId, 1, "OPEN_CONFIRMED");
        assertThat(stepState(orderId, 1)).isEqualTo("OPEN_CONFIRMED");
    }

    @Test
    @DisplayName("FI-14 告警事件：本阶段无处置者，但必须\"可见地没处置\"而不是静默丢弃")
    void alarmEventIsCountedNotSilentlyDropped() {
        long orderId = startedOrder();
        String clientId = PRODUCT_KEY + "::" + deviceId;
        String topic = "swap/v1/up/" + PRODUCT_KEY + "/" + deviceId + "/event";
        double before = counterValue("swap.event.ignored", "alarm");

        ObjectNode data = MAPPER.createObjectNode();
        data.put("eventType", "alarm");
        data.put("code", "TEMP_HIGH");
        data.put("level", "CRITICAL");
        router.dispatch(topic, codec.encode(envelope("FI14-" + SEQ.incrementAndGet(), data)), clientId, 1);
        settle();

        // 状态/资产/权益：安全事件规则引擎属 M5，这里断言的是"没被偷偷推进"
        assertThat(orderState(orderId)).isEqualTo("RETURNING");
        assertThat(activeBindings()).isZero();
        assertThat(rightState(orderId)).isEqualTo("OCCUPIED");
        // 工单与告警：必须有计数——M5 接上处置后这条断言会失败并逼我们更新用例，而不是永远看不见
        assertThat(counterValue("swap.event.ignored", "alarm"))
                .as("alarm 事件必须计入 swap.event.ignored（当前无处置者，但不得静默丢弃）").isGreaterThan(before);
    }

    @Test
    @DisplayName("X-06 无归属事实：落 UNMATCHED_EVENT 差异并留痕，不丢")
    void unmatchedEventBecomesDiscrepancy() {
        // 一台接得进来、但既没建账也没有在途单的设备上报了一个物理事实：
        // 可能是运维手动开门，也可能是拿着 deviceId 伪造的上报——两种都必须先留痕再说。
        String orphanId = "CABO-FI-ORPHAN-" + System.nanoTime();
        var orphan = provision.register(PRODUCT_KEY, orphanId, "无归属上报柜");
        jdbc.update("UPDATE iot_device SET secret_cipher = ?, online_state = 'ONLINE' WHERE id = ?",
                CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), masterSecret()), orphan.deviceRowId());
        long discrepanciesBefore = count("SELECT COUNT(*) FROM swap_discrepancy WHERE kind = 'UNMATCHED_EVENT'");
        long ordersBefore = count("SELECT COUNT(*) FROM swap_order");
        long rightTxBefore = count("SELECT COUNT(*) FROM swap_right_transaction");

        ObjectNode data = MAPPER.createObjectNode();
        data.put("eventType", "battery_detected");
        data.put("slotNo", 1);
        data.put("batteryCode", "BAT-UNKNOWN-1");
        router.dispatch("swap/v1/up/" + PRODUCT_KEY + "/" + orphanId + "/event",
                codec.encode(envelope(orphanId, "X6-" + SEQ.incrementAndGet(), data)),
                PRODUCT_KEY + "::" + orphanId, 1);
        settle();

        // 差异与告警（本条的主断言）
        assertThat(count("SELECT COUNT(*) FROM swap_discrepancy WHERE kind = 'UNMATCHED_EVENT'") - discrepanciesBefore)
                .as("无归属事实必须落一条差异，而不是只进日志").isPositive();
        assertThat(count("SELECT COUNT(*) FROM iot_message_log WHERE device_row_id = ? AND valid_flag = 1",
                orphan.deviceRowId())).as("报文本身也得留痕").isPositive();
        // 状态：没有任何订单被这条报文造出来（全库共用一个 H2，所以只能比增量）
        assertThat(count("SELECT COUNT(*) FROM swap_order") - ordersBefore)
                .as("无归属上报不得造出订单").isZero();
        // 资产：不能因为一个来路不明的上报就改归属
        assertThat(activeBindings()).isZero();
        // 权益：不能被动流水
        assertThat(count("SELECT COUNT(*) FROM swap_right_transaction") - rightTxBefore).isZero();
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    // ---------------- 夹具与工具 ----------------

    private String masterSecret() {
        return "fi-cloud-effects-secret";
    }

    private long cabinetId(long orderId) {
        return jdbc.queryForObject("SELECT cabinet_id FROM swap_order WHERE id = ?", Long.class, orderId);
    }

    private int returnSlotOf(long orderId) {
        return jdbc.queryForObject("SELECT return_slot_no FROM swap_order WHERE id = ?", Integer.class, orderId);
    }

    private long createdOrder() {
        SwapOrderService.CreateResult created = orders.create(member, cabinetNo, "H5", "fi-" + System.nanoTime());
        if (!created.accepted()) {
            throw new IllegalStateException("建单被拒：" + created.rejectReasons());
        }
        return created.orderId();
    }

    /**
     * 建单后把现场摆成"归还中 + S1 已下发"。
     *
     * 为什么不直接调 {@code flow.startReturn}：test profile 没有真 Broker，
     * startReturn 卡在"指令必须真的 DISPATCHED 才推进"这一条会直接拒（那是正确行为，
     * M2 加上去的），而本用例要测的是**事件匹配与分支判定**，不是指令送达性——
     * 后者由 CI 的 cross-process job 拿两个真进程守。
     */
    private long startedOrder() {
        long orderId = createdOrder();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("UPDATE swap_order SET order_state = 'RETURNING', update_time = ? WHERE id = ?", now, orderId);
        jdbc.update("UPDATE swap_order_step SET step_state = 'DISPATCHED', update_time = ? WHERE order_id = ? "
                + "AND step_no = 1", now, orderId);
        assertThat(orderState(orderId)).as("前置：订单必须在归还中").isEqualTo("RETURNING");
        assertThat(stepState(orderId, 1)).as("前置：S1 必须已下发").isEqualTo("DISPATCHED");
        return orderId;
    }

    private Envelope event(String eventType, int slotNo, String batteryCode, String msgId) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("eventType", eventType);
        data.put("slotNo", slotNo);
        if (batteryCode != null) {
            data.put("batteryCode", batteryCode);
        }
        return envelope(msgId, data);
    }

    private Envelope envelope(String msgId, ObjectNode data) {
        return envelope(deviceId, msgId, data);
    }

    private Envelope envelope(String fromDevice, String msgId, ObjectNode data) {
        long now = System.currentTimeMillis();
        Envelope unsigned = new Envelope("1.0", msgId, now, now + 60_000L, nonce(), "trace-fi", null,
                PRODUCT_KEY + "::" + fromDevice, null, 1L, null, null, data, null);
        String sign = deviceSecrets.sign(deviceSecrets.deriveMsgSecret(masterSecret()), unsigned);
        return new Envelope("1.0", msgId, unsigned.issuedAt(), unsigned.expireAt(), unsigned.nonce(), "trace-fi",
                null, unsigned.from(), null, 1L, null, null, data, sign);
    }

    private static String nonce() {
        return "nonce-" + UUID.randomUUID();
    }

    private String orderState(long orderId) {
        return repo.findOrder(orderId).state();
    }

    private String rightState(long orderId) {
        return repo.findOrder(orderId).rightState();
    }

    private String stepState(long orderId, int stepNo) {
        Map<String, Object> step = repo.step(orderId, stepNo);
        return step == null ? null : String.valueOf(step.get("step_state"));
    }

    private int activeBindings() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM swap_battery_binding WHERE bind_state = 'ACTIVE'",
                Integer.class);
        return n == null ? 0 : n;
    }

    private int discrepancies(long orderId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM swap_discrepancy WHERE order_id = ?", Integer.class,
                orderId);
        return n == null ? 0 : n;
    }

    private double counterValue(String name, String typeTag) {
        var counter = registry.find(name).tag("type", typeTag).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** dispatch 是异步的（不阻塞 Broker 线程），所以断言前必须等；等待本身不放宽断言。 */
    private void awaitStep(long orderId, int stepNo, String expected) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !expected.equals(stepState(orderId, stepNo))) {
            settle(100);
        }
    }

    private void settle() {
        settle(300);
    }

    private void settle(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long seedMember() {
        long id = 975_000L + SEQ.incrementAndGet();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO member_user (id, member_no, nickname, realname_state, member_state, register_source, "
                        + "create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'VERIFIED', 'NORMAL', 'H5', ?,?, 0, 0, 1)",
                id, "M" + id, "云侧 FI 会员", now, now);
        jdbc.update("INSERT INTO swap_right_account (id, member_id, plan_id, times_total, times_used, times_occupied, "
                        + "valid_from, valid_until, freeze_state, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?, ?, 1, 60, 0, 1, ?, '2099-12-31 00:00:00', 'NORMAL', ?, ?, 0, 0, 1)",
                id * 10, id, now, now, now);
        return id;
    }
}
