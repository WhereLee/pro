package com.lrs.buddy.framework.tenant;

import com.lrs.buddy.biz.barrier.mapper.BarrierMapper;
import com.lrs.buddy.biz.barrier.entity.Barrier;
import com.lrs.buddy.framework.config.AsyncConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多租户端到端实证（开启 {@code buddy.tenant.enabled=true}）：证明 TenantLineInnerInterceptor 真实改写 SQL。
 *
 * <p>用独立内存库 {@code buddytenant}（区别于其它测试的 {@code buddytest}），避免跨上下文数据污染。
 * 覆盖：INSERT 自动注入 tenant_id、SELECT 按当前租户过滤、租户间隔离、callIgnoring 跨租户放行、
 * 无上下文回退默认租户、TaskDecorator 跨线程传播。
 */
@SpringBootTest(properties = {
        "buddy.tenant.enabled=true",
        "barrier.scheduler.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:buddytenant;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@ActiveProfiles("test")
class TenantIsolationTest {

    @Autowired
    private BarrierMapper barrierMapper;

    @Autowired
    @Qualifier(AsyncConfig.TASK_EXECUTOR)
    private Executor taskExecutor;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private Barrier newBarrier(String name) {
        Barrier b = new Barrier();
        b.setName(name);
        b.setEnabled(1);
        return b;
    }

    private List<String> visibleNames(Long tenantId) {
        List<Barrier> rows = tenantId == null
                ? barrierMapper.selectList(null)
                : TenantContext.callAs(tenantId, () -> barrierMapper.selectList(null));
        return rows.stream().map(Barrier::getName).toList();
    }

    @Test
    @DisplayName("租户隔离：INSERT 落各自租户，SELECT 只见本租户，互不可见")
    void isolation() {
        String t1 = "iso-t1-" + System.nanoTime();
        String t2 = "iso-t2-" + System.nanoTime();
        TenantContext.runAs(1L, () -> barrierMapper.insert(newBarrier(t1)));
        TenantContext.runAs(2L, () -> barrierMapper.insert(newBarrier(t2)));

        assertThat(visibleNames(1L)).contains(t1).doesNotContain(t2);
        assertThat(visibleNames(2L)).contains(t2).doesNotContain(t1);
    }

    @Test
    @DisplayName("callIgnoring：跨租户全部可见（系统级操作放行）")
    void ignoreSeesAll() {
        String a = "ign-a-" + System.nanoTime();
        String b = "ign-b-" + System.nanoTime();
        TenantContext.runAs(1L, () -> barrierMapper.insert(newBarrier(a)));
        TenantContext.runAs(2L, () -> barrierMapper.insert(newBarrier(b)));

        List<String> all = TenantContext.callIgnoring(() -> barrierMapper.selectList(null))
                .stream().map(Barrier::getName).toList();
        assertThat(all).contains(a, b);
    }

    @Test
    @DisplayName("无租户上下文 → 回退默认租户 1：插入落 1、仅租户 1 可见")
    void fallbackDefault() {
        String d = "def-" + System.nanoTime();
        barrierMapper.insert(newBarrier(d));   // 未设置上下文
        assertThat(visibleNames(1L)).contains(d);
        assertThat(visibleNames(2L)).doesNotContain(d);
    }

    @Test
    @DisplayName("TaskDecorator：租户上下文跨线程传播到 @Async 线程池")
    void propagatesAcrossThreads() throws Exception {
        TenantContext.setTenantId(77L);
        AtomicReference<Long> seen = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        taskExecutor.execute(() -> {
            seen.set(TenantContext.getTenantId());
            latch.countDown();
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isEqualTo(77L);
    }
}
