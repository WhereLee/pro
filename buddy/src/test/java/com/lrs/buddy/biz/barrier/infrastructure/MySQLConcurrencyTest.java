package com.lrs.buddy.biz.barrier.infrastructure;

import com.lrs.buddy.biz.barrier.core.BarrierEngine;
import com.lrs.buddy.biz.barrier.core.BarrierState;
import com.lrs.buddy.biz.barrier.core.BarrierStatusView;
import com.lrs.buddy.biz.barrier.core.BarrierStore;
import com.lrs.buddy.biz.barrier.core.OptimisticLockConflictException;
import com.lrs.buddy.biz.barrier.core.TriggerSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 MySQL 并发实证：证明 {@code @Version} CAS 在真实 InnoDB 上成立（H2 掩盖不了的语义）。
 *
 * <p>单 JVM 内引擎有按杆锁串行，跨实例 CAS 冲突不会自然发生；故 {@link #concurrentCasOnlyOneWins()}
 * 直接并发调用 {@code store.apply}（绕过引擎锁）模拟"两个实例拿同一基线版本写同一杆"，验证：
 * 恰好一胜一冲突、版本只 +1、败者事务回滚不留孤儿事件。{@link #engineConcurrentTriggersOnRealMySQL()}
 * 则验证引擎级多线程（按杆锁串行）在真库上无异常、终态有效。
 *
 * <p>门控同框架 {@code MysqlConsistencyTest}：{@code @Tag("mysql")} 由 surefire 默认排除，
 * 用 {@code -Dsurefire.excludedGroups=} 反选在真库(buddy_it)上运行；每次 {@code clean+migrate} 重置。
 */
@Tag("mysql")
@ActiveProfiles("mysql-it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        "spring.flyway.clean-disabled=false",
        "barrier.scheduler.enabled=false"
})
class MySQLConcurrencyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long B1 = 1L;

    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BarrierEngine engine;
    @Autowired
    private BarrierStore store;

    @BeforeAll
    void migrateFresh() {
        flyway.clean();
        flyway.migrate();
    }

    @Test
    @DisplayName("两写者同版本并发 apply → 一胜一冲突、版本只+1、败者回滚不留孤儿事件")
    void concurrentCasOnlyOneWins() throws Exception {
        engine.manual(B1, BarrierState.OPEN, ZonedDateTime.now(ZONE), 1L);   // 建立已知版本的状态行
        BarrierStatusView base = store.loadStatus(B1);
        Long v = base.version();
        assertThat(v).isNotNull();

        int writers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            final long op = 100L + i;
            fs.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    // 两写者都拿同一基线版本 v 直接落库（绕过引擎按杆锁，模拟两个实例）
                    store.apply(B1, new BarrierStatusView(BarrierState.CLOSED, true, base.lastScheduled(), v),
                            BarrierState.CLOSED, TriggerSource.MANUAL, LocalDateTime.now(), "并发手动", op);
                    ok.incrementAndGet();
                } catch (OptimisticLockConflictException e) {
                    conflict.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        for (Future<?> f : fs) {
            f.get();
        }

        assertThat(ok.get()).isEqualTo(1);                                  // 恰好一个写者成功
        assertThat(conflict.get()).isEqualTo(1);                            // 另一个 CAS 冲突
        assertThat(store.loadStatus(B1).version()).isEqualTo(v + 1);        // 版本只 +1
        assertThat(store.loadStatus(B1).state()).isEqualTo(BarrierState.CLOSED);
        assertThat(countRaceEvents()).isEqualTo(1);                         // 只落一条事件（败者回滚，无孤儿）
    }

    @Test
    @DisplayName("引擎级多线程 manual+reconcile（同杆）→ 按杆锁串行、无异常逃逸、终态有效")
    void engineConcurrentTriggersOnRealMySQL() throws Exception {
        engine.alignOnStartup(ZonedDateTime.now(ZONE));
        int n = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int k = i;
            fs.add(pool.submit(() -> {
                try {
                    go.await();
                    if (k % 2 == 0) {
                        engine.manual(B1, k % 4 == 0 ? BarrierState.OPEN : BarrierState.CLOSED,
                                ZonedDateTime.now(ZONE), (long) k);
                    } else {
                        engine.reconcile(ZonedDateTime.now(ZONE));
                    }
                } catch (Throwable t) {
                    err.compareAndSet(null, t);
                }
                return null;
            }));
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        for (Future<?> f : fs) {
            f.get();
        }

        assertThat(err.get()).isNull();                                     // 无异常逃逸
        BarrierStatusView s = store.loadStatus(B1);
        assertThat(s.state()).isIn(BarrierState.OPEN, BarrierState.CLOSED);
        assertThat(s.version()).isNotNull();
    }

    /** 本次 CAS 竞态两个写者用的 operator_id 固定为 100/101，据此精确数它们落库的事件数。 */
    private int countRaceEvents() {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM barrier_event WHERE barrier_id = 1 AND operator_id IN (100, 101)",
                Integer.class);
        return c == null ? 0 : c;
    }
}
