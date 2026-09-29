package com.lrs.buddy.biz.barrier.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyResolverTest {

    @Test
    @DisplayName("取优先级最高的启用策略")
    void picksHighestPriorityEnabled() {
        List<StrategySpec> list = List.of(
                new StrategySpec(1L, "低", 10, 1),
                new StrategySpec(2L, "高", 100, 1),
                new StrategySpec(3L, "中", 50, 1));
        assertThat(StrategyResolver.pickActive(list)).get()
                .extracting(StrategySpec::id).isEqualTo(2L);
    }

    @Test
    @DisplayName("停用策略被排除")
    void excludesDisabled() {
        List<StrategySpec> list = List.of(
                new StrategySpec(1L, "停用高优先", 999, 0),
                new StrategySpec(2L, "启用", 10, 1));
        assertThat(StrategyResolver.pickActive(list)).get()
                .extracting(StrategySpec::id).isEqualTo(2L);
    }

    @Test
    @DisplayName("同优先级取 id 最小")
    void tieBreaksBySmallestId() {
        List<StrategySpec> list = List.of(
                new StrategySpec(5L, "a", 100, 1),
                new StrategySpec(3L, "b", 100, 1));
        assertThat(StrategyResolver.pickActive(list)).get()
                .extracting(StrategySpec::id).isEqualTo(3L);
    }

    @Test
    @DisplayName("全停用/空 → 无生效策略")
    void noneWhenEmptyOrAllDisabled() {
        assertThat(StrategyResolver.pickActive(List.of())).isEmpty();
        assertThat(StrategyResolver.pickActive(List.of(new StrategySpec(1L, "x", 1, 0)))).isEmpty();
        assertThat(StrategyResolver.pickActive(null)).isEmpty();
    }
}
