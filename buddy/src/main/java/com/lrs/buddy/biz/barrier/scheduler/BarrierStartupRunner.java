package com.lrs.buddy.biz.barrier.scheduler;

import com.lrs.buddy.biz.barrier.core.BarrierEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 启动对齐：应用就绪后按当前时刻把杆对齐到计划应有状态（不恢复内存/手动临时态）。
 *
 * <p>用 {@link ApplicationRunner} 而非 {@code @PostConstruct}：确保 sql.init 已建表后再读计划/写状态。
 */
@Component
public class BarrierStartupRunner implements ApplicationRunner {

    private final BarrierEngine engine;
    private final ZoneId zone;

    public BarrierStartupRunner(BarrierEngine engine, @Value("${barrier.zone:Asia/Shanghai}") String zone) {
        this.engine = engine;
        this.zone = ZoneId.of(zone);
    }

    @Override
    public void run(ApplicationArguments args) {
        engine.alignOnStartup(ZonedDateTime.now(zone));
    }
}
