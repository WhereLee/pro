package com.lrs.buddy.framework.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.lrs.buddy.framework.tenant.BuddyTenantLineHandler;
import com.lrs.buddy.framework.tenant.TenantProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>MP 3.4 起旧的 {@code PaginationInterceptor} 已被 {@code MybatisPlusInterceptor} 取代，
 * 3.5 中彻底移除——这是很多老项目升级时第一个编译不过的地方。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(TenantProperties tenantProperties,
                                                         BuddyTenantLineHandler tenantLineHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 多租户：必须最先加入（MP 要求 tenant 在 pagination 之前，否则会先分页再改写导致 count/limit 错位）；
        // 仅在 buddy.tenant.enabled=true 时接入，关闭时等价于单租户、零行为变化
        if (tenantProperties.isEnabled()) {
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler));
        }

        // 分页：指定数据库类型，MP 才能生成正确的物理分页语句
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        // 乐观锁：配合实体上的 @Version，更新时自动追加 where version = ?
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 防全表更新/删除：拦截不带 where 条件的 update/delete，避免误操作清空整表
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }
}
