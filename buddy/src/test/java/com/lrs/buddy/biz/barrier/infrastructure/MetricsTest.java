package com.lrs.buddy.biz.barrier.infrastructure;

import com.lrs.buddy.AbstractIntegrationTest;
import com.lrs.buddy.biz.barrier.core.BarrierEngine;
import com.lrs.buddy.biz.barrier.core.BarrierState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标埋点冒烟（H2 默认档）：手动操作后 apply 计数递增、per-barrier 状态 gauge 注册且反映当前态。
 * 复用框架集成测试基类的共享上下文（profile=test 已关心跳调度）。
 */
class MetricsTest extends AbstractIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private MeterRegistry registry;
    @Autowired
    private BarrierEngine engine;

    @Test
    @DisplayName("手动开合 → barrier.apply{source=MANUAL} 递增、barrier.state{barrierId=1} gauge=OPEN")
    void manualRecordsMetrics() {
        engine.manual(1L, BarrierState.CLOSED, ZonedDateTime.now(ZONE), 1L);   // 先置关，确保下一步是状态跃迁
        double before = registry.counter("barrier.apply", "source", "MANUAL").count();

        engine.manual(1L, BarrierState.OPEN, ZonedDateTime.now(ZONE), 1L);
        double after = registry.counter("barrier.apply", "source", "MANUAL").count();

        assertThat(after).isGreaterThan(before);

        Gauge gauge = registry.find("barrier.state").tag("barrierId", "1").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0);   // OPEN
    }
}
