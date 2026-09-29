package com.lrs.buddy.framework.config;

import com.lrs.buddy.framework.security.UserContext;
import com.lrs.buddy.framework.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池。
 *
 * <p>为什么不用默认的 {@code SimpleAsyncTaskExecutor}：它每次任务都新建线程，
 * 高并发下会瞬间创建大量线程导致 OOM。这里显式定义有界线程池。
 *
 * <p>参数选择的理由：
 * <ul>
 *   <li>核心/最大线程数按 CPU 核数设定——操作日志落库是 IO 密集型（写库），
 *       线程数可略高于核数</li>
 *   <li>队列容量 512，满了之后才扩容到最大线程数</li>
 *   <li>拒绝策略 {@code CallerRunsPolicy}：由提交任务的线程自己执行。
 *       日志写入被降级为同步执行，虽然慢一点，但不会丢日志——
 *       比直接抛异常或静默丢弃更合适</li>
 * </ul>
 */
@Slf4j
@EnableAsync
@Configuration
public class AsyncConfig {

    public static final String TASK_EXECUTOR = "buddyTaskExecutor";

    @Bean(TASK_EXECUTOR)
    public Executor taskExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cores);
        executor.setMaxPoolSize(cores * 2);
        executor.setQueueCapacity(512);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("buddy-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 上下文传播：@Async 任务在新线程执行，ThreadLocal 不会自动带过去。
        // 这里把提交线程的用户/租户/安全上下文快照复制到执行线程，任务结束后清理，避免线程池串号。
        executor.setTaskDecorator(contextPropagatingDecorator());
        // 容器关闭时等待任务执行完，避免日志写到一半进程就退出
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * TaskDecorator：把提交任务时所在线程的 UserContext / TenantContext / SecurityContext
     * 复制到实际执行的线程，并在任务结束后清理。
     *
     * <p>没有它，@Async 方法里 SecurityUtils.getLoginUser()、MP 自动填充(createBy)、
     * 租户拦截器都会因 ThreadLocal 为空而失效——这是异步场景最隐蔽的坑。
     */
    private TaskDecorator contextPropagatingDecorator() {
        return runnable -> {
            Long userId = UserContext.getUserId();
            String username = UserContext.getUsername();
            Long tenantId = TenantContext.getTenantId();
            boolean ignore = TenantContext.isIgnore();
            SecurityContext securityContext = SecurityContextHolder.getContext();
            return () -> {
                try {
                    if (userId != null) {
                        UserContext.set(userId, username);
                    }
                    if (tenantId != null) {
                        TenantContext.setTenantId(tenantId);
                    }
                    TenantContext.setIgnore(ignore);
                    SecurityContextHolder.setContext(securityContext);
                    runnable.run();
                } finally {
                    UserContext.clear();
                    TenantContext.clear();
                    SecurityContextHolder.clearContext();
                }
            };
        };
    }

    /**
     * 异步方法抛出的异常默认只打印到控制台，容易被忽略，这里统一接日志。
     */
    @Bean
    public AsyncUncaughtExceptionHandler asyncUncaughtExceptionHandler() {
        return new AsyncUncaughtExceptionHandler() {
            @Override
            public void handleUncaughtException(Throwable ex, Method method, Object... params) {
                log.error("异步任务执行异常，method={}", method.getName(), ex);
            }
        };
    }
}
