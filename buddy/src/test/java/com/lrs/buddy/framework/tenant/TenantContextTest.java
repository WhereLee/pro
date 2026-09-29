package com.lrs.buddy.framework.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** TenantContext 纯单测：ThreadLocal 读写、runAs/callAs 恢复、callIgnoring、嵌套、异常安全、线程隔离。 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("set/get/clear：设置后可读，清理后为 null")
    void setGetClear() {
        assertThat(TenantContext.getTenantId()).isNull();
        TenantContext.setTenantId(42L);
        assertThat(TenantContext.getTenantId()).isEqualTo(42L);
        TenantContext.clear();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("runAs：执行期间为指定租户，结束后恢复原值（含原值为 null）")
    void runAsRestores() {
        TenantContext.runAs(7L, () -> assertThat(TenantContext.getTenantId()).isEqualTo(7L));
        assertThat(TenantContext.getTenantId()).isNull();   // 原值 null → 恢复为 null

        TenantContext.setTenantId(1L);
        TenantContext.runAs(2L, () -> assertThat(TenantContext.getTenantId()).isEqualTo(2L));
        assertThat(TenantContext.getTenantId()).isEqualTo(1L);   // 恢复外层租户
    }

    @Test
    @DisplayName("callAs：有返回值版本，同样恢复")
    void callAsReturns() {
        Long seen = TenantContext.callAs(9L, TenantContext::getTenantId);
        assertThat(seen).isEqualTo(9L);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("runAs 嵌套：内层结束恢复外层租户")
    void nested() {
        TenantContext.runAs(1L, () -> {
            assertThat(TenantContext.getTenantId()).isEqualTo(1L);
            TenantContext.runAs(2L, () -> {
                assertThat(TenantContext.getTenantId()).isEqualTo(2L);
                TenantContext.runAs(3L, () -> assertThat(TenantContext.getTenantId()).isEqualTo(3L));
                assertThat(TenantContext.getTenantId()).isEqualTo(2L);
            });
            assertThat(TenantContext.getTenantId()).isEqualTo(1L);
        });
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("callIgnoring：执行期间 isIgnore=true，结束后恢复")
    void callIgnoring() {
        assertThat(TenantContext.isIgnore()).isFalse();
        String r = TenantContext.callIgnoring(() -> {
            assertThat(TenantContext.isIgnore()).isTrue();
            return "ok";
        });
        assertThat(r).isEqualTo("ok");
        assertThat(TenantContext.isIgnore()).isFalse();
    }

    @Test
    @DisplayName("异常安全：runAs 内抛异常也会恢复上下文（finally）")
    void restoresOnException() {
        TenantContext.setTenantId(5L);
        try {
            TenantContext.runAs(6L, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException ignored) {
            // 预期
        }
        assertThat(TenantContext.getTenantId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("线程隔离：另一线程读不到本线程的租户（ThreadLocal 语义）")
    void threadIsolation() throws Exception {
        TenantContext.setTenantId(100L);
        AtomicReference<Long> other = new AtomicReference<>(-1L);
        Thread t = new Thread(() -> other.set(TenantContext.getTenantId()));
        t.start();
        t.join();
        assertThat(other.get()).isNull();
    }
}
