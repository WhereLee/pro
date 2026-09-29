package com.lrs.buddy.biz.barrier.core;

/**
 * 策略规格（核心层值对象，不含持久化注解）。
 * priority 越大越优先；enabled==1 表示启用。
 */
public record StrategySpec(Long id, String name, int priority, int enabled) {
    public boolean isEnabled() {
        return enabled == 1;
    }
}
