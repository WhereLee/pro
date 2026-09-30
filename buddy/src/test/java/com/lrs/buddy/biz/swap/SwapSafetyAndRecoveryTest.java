package com.lrs.buddy.biz.swap;

import com.lrs.buddy.biz.swap.provision.DeviceProvisionService;
import com.lrs.buddy.biz.swap.recovery.SwapRecoveryService;
import com.lrs.buddy.biz.swap.repo.SwapOrderRepository;
import com.lrs.buddy.biz.swap.safety.SwapSafetyLinkageService;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全联动与重启现场重建（M3 阶段 1 收口）。
 *
 * 两条各自守住一个"看起来正常、其实是事故"的形状：
 * <ul>
 *   <li>安全联动：<b>自动中止与人工中止在状态机上必须是两条路</b>。
 *       如果都用 ADMIN_ABORT，事后无法回答"这单是安全规则停的还是运营停的"，
 *       而前者不该占用双人复核的语义通道（真出事时等审批就是拿安全换流程干净）。</li>
 *   <li>重启重建：<b>非终态 + 无 deadline = 永久悬挂</b>。这种单在页面上看是"进行中"，
 *       超时驱动却永远扫不到它，是最难发现的一类死单。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class SwapSafetyAndRecoveryTest {

    private static final String PRODUCT_KEY = "SWAP-CAB-8";
    private static final String BATTERY_PRODUCT = "BAT-60V20AH";
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private SwapSafetyLinkageService safety;
    @Autowired
    private SwapRecoveryService recovery;
    @Autowired
    private SwapOrderService orders;
    @Autowired
    private SwapLedgerService ledger;
    @Autowired
    private SwapOrderRepository repo;
    @Autowired
    private DeviceProvisionService provision;
    @Autowired
    private IotProperties properties;
    @Autowired
    private JdbcTemplate jdbc;

    private String cabinetNo;
    private long cabinetId;
    private long siteId;
    private long member;

    @BeforeEach
    void setUp() {
        long stamp = System.nanoTime();
        cabinetNo = "CAB-SF-" + stamp;
        member = seedMember();
        siteId = seedSite(stamp);
        var credential = provision.register(PRODUCT_KEY, "CABO-SF-" + stamp, "安全联动柜");
        jdbc.update("UPDATE iot_device SET secret_cipher = ?, online_state = 'ONLINE' WHERE id = ?",
                CryptoUtil.aesGcmEncrypt(properties.getDeviceSecretKey(), "sf-secret-" + stamp),
                credential.deviceRowId());
        // 用专属站点而不是共享的站点 1：站点级联动会扫站内全部柜机，而所有测试柜机都合住在 site=1，
        // 跑一轮就会把别的用例的柜机一起锁成 SAFETY_LOCKED（日志里能看到十几条给无关柜机的停充记录）。
        ledger.createCabinet(siteId, PRODUCT_KEY, cabinetNo, credential.deviceId(), 8, null, null);
        ledger.registerBattery(cabinetNo, 1, "BAT-SF-" + stamp, BATTERY_PRODUCT, 96,
                new BigDecimal("26.0"), new BigDecimal("20.0"), new BigDecimal("60.0"));
        cabinetId = repo.findCabinet(cabinetNo).id();
    }

    @Test
    @DisplayName("安全联动：锁柜 + 停充指令 + 在途单走自动中止路径（不占人工通道）")
    void emergencyStopLocksAndAbortsInfightOrder() {
        long orderId = createdOrder();
        assertThat(repo.findInfightByCabinet(cabinetId)).as("联动前该柜有一笔在途单").hasSize(1);

        SwapSafetyLinkageService.LinkageResult result =
                safety.emergencyStopSite(siteId, "站点温度告警升级，联动停充", "ALARM-DEMO-1");

        assertThat(result.cabinets()).as("本用例的站点只有一台柜").isEqualTo(1);
        assertThat(result.stopCommandsFailed()).as("停充不能只记日志不看失败数").isZero();
        assertThat(result.ordersAbortFailed()).isZero();
        assertThat(jdbc.queryForObject("SELECT cabinet_state FROM swap_cabinet WHERE id = ?", String.class, cabinetId))
                .isEqualTo("SAFETY_LOCKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iot_command WHERE cmd_code = 'EMERGENCY_STOP' "
                + "AND biz_type = 'SWAP_SAFETY' AND biz_id = ?", Integer.class, cabinetId))
                .as("停充指令必须真的下发过（不是只改库状态）").isEqualTo(1);
        assertThat(orderState(orderId)).as("安全路径落 ABORTED，事件是 alarm_safety_lock")
                .isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_order_event WHERE order_id = ? "
                + "AND event_type = 'alarm_safety_lock'", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT times_occupied FROM swap_right_account WHERE member_id = ?",
                Integer.class, member)).as("中止必须同步退预占").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM swap_compensation WHERE action = 'ESCALATE_ALARM' "
                + "AND comp_state = 'PENDING'", Integer.class))
                .as("告警升级挂台账（M5 才有执行者），不能只写日志").isPositive();
    }

    @Test
    @DisplayName("安全联动：同一站点重复触发时，第二次停充指令仍必须发得出去")
    void repeatedLinkageStillDispatchesSecondStop() {
        // 这是本用例存在的唯一理由：第一次停充指令还在途（test profile 下不会收到应答），
        // 不先置 SUPERSEDED 就会撞 uk_icmd_active，而异常被 per-cabinet catch 吞掉——
        // 看上去“联动跑了、方法照样返回成功”，柜机其实只收到过一次停充。
        safety.emergencyStopSite(siteId, "首次：烟感触发", null);
        SwapSafetyLinkageService.LinkageResult second =
                safety.emergencyStopSite(siteId, "第二次：重复上报", null);

        assertThat(second.stopCommandsFailed()).as("第二次联动不能因为旧指令在途就静默失败").isZero();
        assertThat(second.stopCommandsSent()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iot_command WHERE biz_type = 'SWAP_SAFETY' "
                + "AND biz_id = ?", Integer.class, cabinetId))
                .as("两次联动 = 两条指令留痕").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iot_command WHERE biz_type = 'SWAP_SAFETY' "
                + "AND biz_id = ? AND cmd_state = 'SUPERSEDED'", Integer.class, cabinetId))
                .as("被接管的那条必须留下 SUPERSEDED，而不是消失").isEqualTo(1);
    }

    @Test
    @DisplayName("安全联动必须写明依据；锁柜可重复调用且不覆盖首次原因")
    void linkageRequiresReasonAndLockIsIdempotent() {
        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> safety.emergencyStopSite(siteId, "短", null)).isInstanceOf(IllegalArgumentException.class));

        safety.emergencyStopSite(siteId, "首次：烟感触发", null);
        String first = jdbc.queryForObject("SELECT locked_reason FROM swap_cabinet WHERE id = ?", String.class, cabinetId);
        safety.emergencyStopSite(siteId, "第二次：重复上报", null);

        assertThat(jdbc.queryForObject("SELECT locked_reason FROM swap_cabinet WHERE id = ?", String.class, cabinetId))
                .as("已锁着的柜机不能被后到的事件冲掉原因，否则现场查不到第一次触发依据")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("重启重建：非终态且无 deadline 的单必须被补上 deadline")
    void recoveryRestoresMissingDeadline() {
        long orderId = createdOrder();
        jdbc.update("UPDATE swap_order SET deadline_ts = NULL, deadline_at = NULL WHERE id = ?", orderId);

        SwapRecoveryService.RecoveryResult result = recovery.recoverOnce();

        assertThat(result.deadlinesRestored()).as("至少要补回我们刚置空的那条").isPositive();
        var row = jdbc.queryForMap("SELECT deadline_ts, deadline_at FROM swap_order WHERE id = ?", orderId);
        assertThat(row.get("deadline_ts")).as("没有 deadline 的在途单永远不会被超时驱动扫到").isNotNull();
        assertThat(row.get("deadline_at")).as("两个口径必须同时补，否则兜底扫描与实际判断不一致").isNotNull();
    }

    @Test
    @DisplayName("重启重建：ABORTING 单只有在阻塞补偿做完后才落终态")
    void recoveryFinalizesOnlyWhenCompensationDone() {
        long orderId = createdOrder();
        stampAborting(orderId);
        long battery = anyBattery();
        insertCompensation(orderId, "BATTERY_PENDING_PICKUP", battery, "PENDING");

        SwapRecoveryService.RecoveryResult waiting = recovery.recoverOnce();
        assertThat(orderState(orderId)).as("补偿没做完就不能因为重启而落终态").isEqualTo("ABORTING");
        assertThat(waiting.ordersStillWaiting()).isPositive();

        jdbc.update("UPDATE swap_compensation SET comp_state = 'DONE', done_at = ? WHERE order_id = ?",
                Timestamp.valueOf(LocalDateTime.now()), orderId);
        SwapRecoveryService.RecoveryResult done = recovery.recoverOnce();

        assertThat(orderState(orderId)).isEqualTo("ABORTED");
        assertThat(done.ordersFinalized()).isPositive();
        // 幂等：再来一次不会重复落终态、也不会把已完成的单再推一遍
        assertThat(recovery.recoverOnce().ordersFinalized()).isZero();
        assertThat(orderState(orderId)).isEqualTo("ABORTED");
    }

    // ---------------- 夹具 ----------------

    private long createdOrder() {
        SwapOrderService.CreateResult created = orders.create(member, cabinetNo, "H5", "sf-" + System.nanoTime());
        if (!created.accepted()) {
            throw new IllegalStateException("建单被拒：" + created.rejectReasons());
        }
        return created.orderId();
    }

    private void stampAborting(long orderId) {
        jdbc.update("UPDATE swap_order SET order_state = 'ABORTING', update_time = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)), orderId);
    }

    private void insertCompensation(long orderId, String action, Long targetId, String state) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO swap_compensation (id, order_id, action, target_type, target_id, comp_state, "
                        + "attempts, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?,?,?,?, 0, ?, ?, 0, 0, 1)",
                810_000_000L + SEQ.incrementAndGet(), orderId, action, "BATTERY", targetId, state, now, now);
    }

    private String orderState(long orderId) {
        return repo.findOrder(orderId).state();
    }

    private long anyBattery() {
        return jdbc.queryForObject("SELECT id FROM swap_battery WHERE del_flag = 0 ORDER BY id DESC LIMIT 1",
                Long.class);
    }

    /** 专属站点：列名显式列出，不依赖 DDL 里的列序（本项目的 SQL 自误过一次列数）。 */
    private long seedSite(long stamp) {
        long id = 920_000L + SEQ.incrementAndGet();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO swap_site (id, site_no, site_name, product_key, enabled, create_time, "
                        + "update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?,?, 1, ?, ?, 0, 0, 1)",
                id, "SITE-SF-" + stamp, "安全联动专用站点", PRODUCT_KEY, now, now);
        return id;
    }

    private long seedMember() {
        long id = 930_000L + SEQ.incrementAndGet();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO member_user (id, member_no, nickname, realname_state, member_state, register_source, "
                        + "create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?,?,?, 'VERIFIED', 'NORMAL', 'H5', ?,?, 0, 0, 1)",
                id, "M" + id, "安全联动会员", now, now);
        jdbc.update("INSERT INTO swap_right_account (id, member_id, plan_id, times_total, times_used, times_occupied, "
                        + "valid_from, valid_until, freeze_state, create_time, update_time, version, del_flag, tenant_id) "
                        + "VALUES (?, ?, 1, 60, 0, 0, ?, '2099-12-31 00:00:00', 'NORMAL', ?, ?, 0, 0, 1)",
                id * 10, id, now, now, now);
        return id;
    }
}
