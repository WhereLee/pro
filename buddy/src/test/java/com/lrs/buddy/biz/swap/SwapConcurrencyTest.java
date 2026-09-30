package com.lrs.buddy.biz.swap;

import com.lrs.buddy.biz.swap.order.OrderState;
import com.lrs.buddy.biz.swap.provision.DeviceProvisionService;
import com.lrs.buddy.biz.swap.service.SwapLedgerService;
import com.lrs.buddy.biz.swap.service.SwapOrderService;
import com.lrs.buddy.framework.common.util.CryptoUtil;
import com.lrs.buddy.framework.iot.config.IotProperties;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真并发实测（M2 验证门"真 InnoDB 仓位抢占"的进程内那一层）。
 *
 * 为什么必须并发跑而不是单线程串起来：前面所有用例都只证明了"约束存在"，
 * 证明不了"两个请求真的同时到达时结果仍正确、且失败原因仍然可解释"。
 * 这类 bug 的特征就是单线程永远复现不了。
 *
 * 最有信息量的一条断言是**拒绝原因**：
 * 有分段锁时，抢不到唯一满电仓的第二人应拿到 `NO_OFFER_SLOT`（重算过候选、确实没仓）；
 * 没有锁时他会拿到 `SLOT_RACE_LOST`（撞上别人刚占的仓）。
 * 前者是业务事实，后者是实现竞态的产物——把它钉成断言，锁被误删时测试会立刻红。
 */
@SpringBootTest
@ActiveProfiles("test")
class SwapConcurrencyTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String BATTERY_PRODUCT = "BAT-60V20AH";
    private static final AtomicLong SEQ = new AtomicLong();

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

    @BeforeEach
    void setUp() {
        long stamp = System.nanoTime();
        cabinetNo = "CAB-CC-" + stamp;
        var credential = provision.register(PRODUCT_KEY, "CABO-CC-" + stamp, "并发测试柜");
        jdbc.update("UPDATE iot_device SET secret_cipher = ? WHERE id = ?",
                CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), "cc-secret-" + stamp),
                credential.deviceRowId());
        ledger.createCabinet(1L, PRODUCT_KEY, cabinetNo, "CABO-CC-" + stamp, 8, null, null);
        jdbc.update("UPDATE iot_device SET online_state = 'ONLINE' WHERE id = ?", credential.deviceRowId());
    }

    @Test
    @DisplayName("两用户抢唯一一个满电仓：一人成、一人被拒且原因是真的没仓，绝不出现同仓双预占")
    void twoUsersRaceForTheOnlyChargedSlot() throws Exception {
        stockBattery(95);
        long userA = seedMember();
        long userB = seedMember();

        List<SwapOrderService.CreateResult> results = runConcurrently(
                () -> orders.create(userA, cabinetNo, "H5", "cc-a"),
                () -> orders.create(userB, cabinetNo, "H5", "cc-b"));

        long authorized = results.stream().filter(r -> r.state() == OrderState.AUTHORIZED).count();
        long rejected = results.stream().filter(r -> r.state() == OrderState.REJECTED).count();
        assertThat(authorized).as("只有一个满电仓，只能成一单").isEqualTo(1);
        assertThat(rejected).isEqualTo(1);

        SwapOrderService.CreateResult loser = results.stream()
                .filter(r -> r.state() == OrderState.REJECTED).findFirst().orElseThrow();
        assertThat(loser.rejectReasons())
                .as("分段锁生效时应重算候选得到业务结论 NO_OFFER_SLOT；出现 SLOT_RACE_LOST 说明锁失效或被删")
                .anyMatch(reason -> reason.startsWith("NO_OFFER_SLOT"));
        assertThat(loser.rejectReasons()).noneMatch(reason -> reason.startsWith("SLOT_RACE_LOST"));

        Integer doubleBooked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT slot_id FROM swap_slot_reservation WHERE resv_state = 'ACTIVE' "
                        + "GROUP BY slot_id HAVING COUNT(*) > 1) t", Integer.class);
        assertThat(doubleBooked).as("active_slot 生成列唯一索引必须保证不双占").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot sl JOIN swap_cabinet c ON c.id = sl.cabinet_id "
                + "WHERE c.cabinet_no = ? AND sl.slot_state = 'RESERVED_ORDER'", Integer.class, cabinetNo))
                .as("在途单只占两个仓：归还仓 + 唯一那个满电仓（必须按本柜统计，H2 库在测试 JVM 内共享）")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("同一用户并发两次下单：只留一单，另一路被唯一索引挡住")
    void sameUserCannotOpenTwoOrdersConcurrently() throws Exception {
        stockBattery(95);
        stockBattery(90);
        long user = seedMember();

        List<Object> outcomes = runConcurrentlyRaw(
                () -> orders.create(user, cabinetNo, "H5", "same-a"),
                () -> orders.create(user, cabinetNo, "H5", "same-b"));

        long succeeded = outcomes.stream().filter(o -> o instanceof SwapOrderService.CreateResult).count();
        assertThat(succeeded).as("同人两笔只能有一笔成立").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_order WHERE user_id = ?", Integer.class, user))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_order WHERE user_id = ? AND order_state = "
                + "'REJECTED'", Integer.class, user))
                .as("被 B3 挡下的那一路不该留下订单行：留下会让拒绝率指标失真").isZero();
    }

    @Test
    @DisplayName("分段锁按柜机隔离：不同柜并发下单互不影响")
    void differentCabinetsDoNotBlockEachOther() throws Exception {
        stockBattery(95);
        String other = "CAB-CC2-" + System.nanoTime();
        var credential = provision.register(PRODUCT_KEY, "CABO-CC2-" + System.nanoTime(), "并发测试柜二");
        jdbc.update("UPDATE iot_device SET online_state = 'ONLINE' WHERE id = ?", credential.deviceRowId());
        ledger.createCabinet(1L, PRODUCT_KEY, other, credential.deviceId(), 4, null, null);
        stockInto(other, 93);   // 第二台柜也得有满电仓，否则它被拒的是 NO_OFFER_SLOT，本用例就测不到"互不阻塞"

        List<SwapOrderService.CreateResult> results = runConcurrently(
                () -> orders.create(seedMember(), cabinetNo, "H5", "multi-a"),
                () -> orders.create(seedMember(), other, "H5", "multi-b"));

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(SwapOrderService.CreateResult::accepted);
        // 统计必须限定在本用例的两笔上：H2 库在整个测试 JVM 内共享，
        // 不限定就会把其它用例留下的 AUTHORIZED 单计进来（那就是“用例互相干拢”，不是被测行为）
        Map<String, Object> counts = jdbc.queryForMap("SELECT COUNT(*) AS total FROM swap_order "
                + "WHERE trace_id IN ('multi-a','multi-b') AND order_state IN ('AUTHORIZED','CREATED')");
        assertThat(((Number) counts.get("total")).intValue()).isEqualTo(2);
    }

    // ---------------- 夹具 ----------------

    private <T> List<T> runConcurrently(Callable<T> first, Callable<T> second) throws Exception {
        return runConcurrentlyRaw(first, second).stream().map(item -> (T) item).toList();
    }

    /**
     * 两个任务在同一瞬间起跑。
     * 用固定 2 线程 + 起跑门闩：比"提交后立刻 get"更可靠地把两次建单压进同一个窗口，
     * 否则测的可能是顺序执行，那等于没测并发。
     *
     * 任务内抛出的异常当成结果值返回：并发下失败是预期行为，
     * 如果让 ExecutionException 直接冒到测试框架，就分不出"被测代码拒绝"与"用例自己写错"。
     */
    private List<Object> runConcurrentlyRaw(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = List.of(
                    pool.submit(() -> {
                        ready.countDown();
                        start.await(10, TimeUnit.SECONDS);
                        return call(first);
                    }),
                    pool.submit(() -> {
                        ready.countDown();
                        start.await(10, TimeUnit.SECONDS);
                        return call(second);
                    }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("两个任务未就绪，本用例等于没跑并发").isTrue();
            start.countDown();
            return List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object call(Callable<?> task) {
        try {
            return task.call();
        } catch (Exception e) {
            return e;
        }
    }

    private void stockBattery(int soc) {
        stockInto(cabinetNo, soc);
    }

    private void stockInto(String targetCabinet, int soc) {
        for (int slotNo = 1; slotNo <= 8; slotNo++) {
            Integer occupied = jdbc.queryForObject("SELECT COUNT(*) FROM swap_slot sl "
                    + "JOIN swap_cabinet c ON c.id = sl.cabinet_id WHERE c.cabinet_no = ? AND sl.slot_no = ? "
                    + "AND sl.battery_id IS NOT NULL", Integer.class, targetCabinet, slotNo);
            if (occupied == null || occupied == 0) {
                ledger.registerBattery(targetCabinet, slotNo, "BAT-CC-" + SEQ.incrementAndGet(), BATTERY_PRODUCT,
                        soc, new BigDecimal("26.0"), new BigDecimal("20.0"), new BigDecimal("60.0"));
                return;
            }
        }
        throw new IllegalStateException("柜内没有空仓可导入电池：" + targetCabinet);
    }

    private long seedMember() {
        long id = 970_000L + SEQ.incrementAndGet();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO member_user (id, member_no, nickname, realname_state, member_state, register_source, "
                        + "create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'VERIFIED', 'NORMAL', 'H5', ?,?, 0, 0, 1)",
                id, "M" + id, "并发会员", now, now);
        jdbc.update("INSERT INTO swap_right_account (id, member_id, plan_id, times_total, times_used, times_occupied, "
                        + "valid_from, valid_until, freeze_state, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?, ?, 1, 60, 0, 0, ?, '2099-12-31 00:00:00', 'NORMAL', ?, ?, 0, 0, 1)",
                id * 10, id, now, now, now);
        return id;
    }
}
