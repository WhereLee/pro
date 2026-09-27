package com.lrs.buddy.modules.job;

import com.lrs.buddy.common.util.SpringContextUtils;
import com.lrs.buddy.modules.job.service.ScheduleJobService;
import com.lrs.buddy.modules.job.task.ITask;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Quartz 任务入口（禁止并发版）。
 *
 * <p>所有定时任务都经过这里，它只做两件事：
 * 按 bean 名从 Spring 容器取出执行体并调用；把执行结果回写到任务记录。
 * 这样业务任务类就是普通 Spring Bean，可以正常注入依赖、被事务管理，
 * 不需要继承任何 Quartz 的类。
 *
 * <p>{@code @DisallowConcurrentExecution} 保证同一个任务不会重叠执行，
 * 适用于"上一次没跑完就不该再跑"的场景（如数据同步、报表生成）。
 * 需要并发的任务请用 {@link ConcurrentQuartzJob}。
 */
@Slf4j
@DisallowConcurrentExecution
public class BuddyQuartzJob extends QuartzJobBean {

    /** JobDataMap 的键名，注册任务与读取任务共用，避免两边写字符串常量不一致 */
    public static final String KEY_BEAN_NAME = "beanName";
    public static final String KEY_PARAMS = "params";
    public static final String KEY_JOB_ID = "jobId";

    @Override
    protected void executeInternal(JobExecutionContext context) {
        String beanName = context.getJobDetail().getJobDataMap().getString(KEY_BEAN_NAME);
        String params = context.getJobDetail().getJobDataMap().getString(KEY_PARAMS);
        Object jobIdValue = context.getJobDetail().getJobDataMap().get(KEY_JOB_ID);
        Long jobId = jobIdValue == null ? null : Long.valueOf(String.valueOf(jobIdValue));

        long start = System.currentTimeMillis();
        try {
            ITask task = SpringContextUtils.getBean(beanName, ITask.class);
            task.run(params);
            recordResult(jobId, true, null);
            log.info("任务执行成功，bean={}，耗时 {}ms", beanName, System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 这里必须吞掉异常：抛给 Quartz 会触发它的重试策略，
            // 对业务任务通常不是期望行为，交由日志与任务记录处理更合适
            recordResult(jobId, false, e.getMessage());
            log.error("任务执行失败，bean={}，耗时 {}ms", beanName, System.currentTimeMillis() - start, e);
        }
    }

    /**
     * 回写执行结果。
     *
     * <p>任务对象由 Quartz 实例化，拿不到注入能力，
     * 只能通过容器工具取 Service；取不到时静默跳过，不影响任务本身。
     */
    private void recordResult(Long jobId, boolean success, String message) {
        if (jobId == null) {
            return;
        }
        try {
            SpringContextUtils.getBean(ScheduleJobService.class).recordResult(jobId, success, message);
        } catch (Exception e) {
            log.warn("回写任务执行结果失败，jobId={}：{}", jobId, e.getMessage());
        }
    }
}
