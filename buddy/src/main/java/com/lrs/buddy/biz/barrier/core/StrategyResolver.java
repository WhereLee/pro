package com.lrs.buddy.biz.barrier.core;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 生效策略选择（纯函数、可单测）：从启用策略里取 priority 最高者；同优先级取 id 最小者。
 */
public final class StrategyResolver {

    private StrategyResolver() {
    }

    public static Optional<StrategySpec> pickActive(List<StrategySpec> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            return Optional.empty();
        }
        return strategies.stream()
                .filter(StrategySpec::isEnabled)
                .max(Comparator.comparingInt(StrategySpec::priority)
                        .thenComparing(Comparator.comparingLong(StrategySpec::id).reversed()));
    }
}
