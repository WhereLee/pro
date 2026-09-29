package com.lrs.buddy.framework.modules.log.aspect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计参数/结果截断的边界回归测试。
 *
 * <p>锁定一个曾导致生产事故的坑：旧实现 {@code substring(0,2000)+"..."} 会得到 2003 字符，
 * 超过 oper_param/json_result 的 VARCHAR(2000) 上限，使审计异步写库失败、审计记录静默丢失。
 */
class OperateLogAspectTest {

    private static final int MAX = 2000;

    @Test
    @DisplayName("null 安全")
    void nullSafe() {
        assertThat(OperateLogAspect.truncate(null)).isNull();
    }

    @Test
    @DisplayName("不超过上限：原样返回")
    void shortTextUnchanged() {
        String s = "x".repeat(MAX);
        assertThat(OperateLogAspect.truncate(s)).hasSize(MAX).isEqualTo(s);
    }

    @Test
    @DisplayName("恰好超 1 字符：截断后总长仍为上限（含省略号），不再溢出")
    void overByOneStaysWithinLimit() {
        String s = "y".repeat(MAX + 1);
        String out = OperateLogAspect.truncate(s);
        assertThat(out).hasSize(MAX);
        assertThat(out).endsWith("...");
    }

    @Test
    @DisplayName("远超上限：截断到上限、以省略号结尾")
    void longTextTruncatedToLimit() {
        String s = "z".repeat(MAX * 3);
        String out = OperateLogAspect.truncate(s);
        assertThat(out).hasSize(MAX);
        assertThat(out).endsWith("...");
        // 省略号占 3 位，正文保留 MAX-3 位
        assertThat(out).startsWith("z".repeat(MAX - 3));
    }
}
