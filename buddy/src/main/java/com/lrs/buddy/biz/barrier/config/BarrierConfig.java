package com.lrs.buddy.biz.barrier.config;

import com.lrs.buddy.biz.barrier.core.BarrierEngine;
import com.lrs.buddy.biz.barrier.core.BarrierStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;

@Configuration
public class BarrierConfig {

    @Bean
    public BarrierEngine barrierEngine(BarrierStore store,
                                       @Value("${barrier.zone:Asia/Shanghai}") String zone) {
        return new BarrierEngine(store, ZoneId.of(zone));
    }
}
