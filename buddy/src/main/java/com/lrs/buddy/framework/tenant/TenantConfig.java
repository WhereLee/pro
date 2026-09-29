package com.lrs.buddy.framework.tenant;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 多租户装配入口：注册 {@link TenantProperties}。
 *
 * <p>租户拦截器的接入在 {@code MybatisPlusConfig}（需与分页/乐观锁/防全表拦截器统一排序），
 * {@link BuddyTenantLineHandler} 与 {@link TenantIgnoreAspect} 各自以 {@code @Component} 注册。
 * 整套能力由 {@code buddy.tenant.enabled} 开关，默认关闭即单租户。
 */
@Configuration
@EnableConfigurationProperties(TenantProperties.class)
public class TenantConfig {
}
