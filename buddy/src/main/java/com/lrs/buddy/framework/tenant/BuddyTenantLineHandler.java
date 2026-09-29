package com.lrs.buddy.framework.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.stereotype.Component;

/**
 * MyBatis-Plus 租户行级处理器：告诉拦截器"当前租户 id、租户列名、哪些表跳过"。
 *
 * <p>仅在 {@code buddy.tenant.enabled=true} 时被 {@code TenantLineInnerInterceptor} 使用；
 * 关闭时该 bean 仍存在但不接入拦截链，零副作用。
 */
@Component
public class BuddyTenantLineHandler implements TenantLineHandler {

    private final TenantProperties properties;

    public BuddyTenantLineHandler(TenantProperties properties) {
        this.properties = properties;
    }

    @Override
    public Expression getTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            // 无上下文（未认证/系统任务）回退到默认租户，保证 SQL 永远拿到合法的 tenant_id 值
            tenantId = properties.getDefaultTenantId();
        }
        return new LongValue(tenantId);
    }

    @Override
    public String getTenantIdColumn() {
        return properties.getColumn();
    }

    @Override
    public boolean ignoreTable(String tableName) {
        // 系统级跨租户操作（登录查找用户、调度心跳、@IgnoreTenant 方法）整体放行
        if (TenantContext.isIgnore()) {
            return true;
        }
        if (tableName == null) {
            return false;
        }
        String name = tableName.replace("`", "").toLowerCase();
        // Quartz 集群表按前缀兜底忽略（无 tenant_id，且由 Quartz 自身管理）
        if (name.startsWith("qrtz_")) {
            return true;
        }
        return properties.getIgnoreTables().stream().anyMatch(t -> t.equalsIgnoreCase(name));
    }
}
