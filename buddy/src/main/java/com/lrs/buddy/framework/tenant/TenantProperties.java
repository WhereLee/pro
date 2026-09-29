package com.lrs.buddy.framework.tenant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.Set;

/**
 * 多租户配置（{@code buddy.tenant.*}）。
 *
 * <p>作为框架能力，默认 {@code enabled=false}——单租户项目零开销、零行为变化；
 * 需要多租户时置 true 即启用 MyBatis-Plus 租户拦截器（表结构已由 V6 预留 tenant_id 列，
 * 启用只是一个配置开关，无需再迁移数据库）。
 */
@Data
@ConfigurationProperties(prefix = "buddy.tenant")
public class TenantProperties {

    /** 是否启用多租户过滤。默认关闭（单租户）。 */
    private boolean enabled = false;

    /** 租户列名。 */
    private String column = "tenant_id";

    /** 无租户上下文（如未认证请求、系统任务）时回退到的默认租户。 */
    private Long defaultTenantId = 1L;

    /**
     * 不参与租户过滤的表：全局字典/目录（如 sys_menu）、框架基础设施表
     * （flyway_schema_history、shedlock）。Quartz 的 QRTZ_* 由 handler 按前缀兜底忽略。
     */
    private Set<String> ignoreTables = new HashSet<>(Set.of(
            "sys_menu", "flyway_schema_history", "shedlock"
    ));
}
