package com.lrs.buddy.framework.tenant;

import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** BuddyTenantLineHandler 纯单测：租户 id 取值/回退、列名、忽略表判定（配置/前缀/标志/大小写/反引号）。 */
class BuddyTenantLineHandlerTest {

    private BuddyTenantLineHandler handler;

    @BeforeEach
    void setUp() {
        TenantProperties props = new TenantProperties();
        props.setColumn("tenant_id");
        props.setDefaultTenantId(1L);
        // ignoreTables 默认含 sys_menu / flyway_schema_history / shedlock
        handler = new BuddyTenantLineHandler(props);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("getTenantId：有上下文取上下文值")
    void tenantIdFromContext() {
        TenantContext.setTenantId(8L);
        assertThat(((LongValue) handler.getTenantId()).getValue()).isEqualTo(8L);
    }

    @Test
    @DisplayName("getTenantId：无上下文回退到默认租户")
    void tenantIdFallbackDefault() {
        assertThat(((LongValue) handler.getTenantId()).getValue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getTenantIdColumn：取配置列名")
    void column() {
        assertThat(handler.getTenantIdColumn()).isEqualTo("tenant_id");
    }

    @Test
    @DisplayName("ignoreTable：配置的忽略表 / 大小写不敏感 / 反引号剥离")
    void ignoreConfigured() {
        assertThat(handler.ignoreTable("sys_menu")).isTrue();
        assertThat(handler.ignoreTable("SYS_MENU")).isTrue();
        assertThat(handler.ignoreTable("`shedlock`")).isTrue();
        assertThat(handler.ignoreTable("flyway_schema_history")).isTrue();
    }

    @Test
    @DisplayName("ignoreTable：Quartz 表按前缀兜底忽略")
    void ignoreQuartz() {
        assertThat(handler.ignoreTable("QRTZ_LOCKS")).isTrue();
        assertThat(handler.ignoreTable("qrtz_triggers")).isTrue();
    }

    @Test
    @DisplayName("ignoreTable：普通业务/框架表不忽略")
    void notIgnoreBusiness() {
        assertThat(handler.ignoreTable("sys_user")).isFalse();
        assertThat(handler.ignoreTable("barrier")).isFalse();
    }

    @Test
    @DisplayName("ignoreTable：ignore 标志置位时全部放行")
    void ignoreWhenFlagSet() {
        TenantContext.setIgnore(true);
        assertThat(handler.ignoreTable("sys_user")).isTrue();
        assertThat(handler.ignoreTable("barrier")).isTrue();
    }

    @Test
    @DisplayName("ignoreTable：null 表名安全返回 false")
    void nullSafe() {
        assertThat(handler.ignoreTable(null)).isFalse();
    }
}
