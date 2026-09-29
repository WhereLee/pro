package com.lrs.buddy.framework.tenant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * {@link IgnoreTenant} 的实现：进入被标注方法前置"忽略租户"标志，退出后恢复（支持嵌套）。
 *
 * <p>与租户拦截器解耦——拦截器读的是 {@link TenantContext#isIgnore()}，本切面只负责按注解开合该标志。
 */
@Aspect
@Component
public class TenantIgnoreAspect {

    @Around("@annotation(com.lrs.buddy.framework.tenant.IgnoreTenant) || @within(com.lrs.buddy.framework.tenant.IgnoreTenant)")
    public Object aroundIgnoreTenant(ProceedingJoinPoint pjp) throws Throwable {
        boolean prev = TenantContext.isIgnore();
        TenantContext.setIgnore(true);
        try {
            return pjp.proceed();
        } finally {
            TenantContext.setIgnore(prev);
        }
    }
}
