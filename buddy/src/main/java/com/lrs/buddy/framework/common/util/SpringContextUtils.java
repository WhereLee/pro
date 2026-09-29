package com.lrs.buddy.framework.common.util;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Spring 容器静态访问工具。
 *
 * <p>适用场景：Quartz 的任务对象由 Quartz 自己实例化（不在 Spring 容器里），
 * 拿不到注入能力，只能通过它去取业务 Bean。
 *
 * <p>注意别滥用：业务代码里出现 {@code getBean} 通常意味着依赖注入没写对，
 * 这里仅为"框架无法控制实例创建过程"的少数场景兜底。
 */
@Component
public class SpringContextUtils implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext context) {
        applicationContext = context;
    }

    public static ApplicationContext context() {
        return applicationContext;
    }

    public static <T> T getBean(Class<T> requiredType) throws NoSuchBeanDefinitionException {
        return applicationContext.getBean(requiredType);
    }

    public static <T> T getBean(String name, Class<T> requiredType) throws NoSuchBeanDefinitionException {
        return applicationContext.getBean(name, requiredType);
    }

    public static Object getBean(String name) throws NoSuchBeanDefinitionException {
        return applicationContext.getBean(name);
    }
}
