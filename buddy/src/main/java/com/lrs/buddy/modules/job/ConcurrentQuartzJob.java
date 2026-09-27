package com.lrs.buddy.modules.job;

import com.lrs.buddy.common.util.SpringContextUtils;
import com.lrs.buddy.modules.job.service.ScheduleJobService;
import com.lrs.buddy.modules.job.task.ITask;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Quartz 任务入口（允许并发版）。
 *
 * <p>与 {@link BuddyQuartzJob} 的唯一区别是没有 {@code @DisallowConcurrentExecution}。
 * 为什么不写成同一个类加参数开关：Quartz 的该注解是类级别的，
 * 运行时无法动态切换，只能用两个类区分。
 */
@Slf4j
public class ConcurrentQuartzJob extends QuartzJobBean {

    @Override
    protected void executeInternal(JobExecutionContext context) {
        String beanName = context.getJobDetail().getJobDataMap().getString(BuddyQuartzJob.KEY_BEAN_NAME);
        String params = context.getJobDetail().getJobDataMap().getString(BuddyQuartzJob.KEY_PARAMS);
        Object jobIdValue = context.getJobDetail().getJobDataMap().get(BuddyQuartzJob.KEY_JOB_ID);
        Long jobId = jobIdValue == null ? null : Long.valueOf(String.valueOf(jobIdValue));

        try {
            ITask task = SpringContextUtils.getBean(beanName, ITask.class);
            task.run(params);
            recordResult(jobId, true, null);
            log.info("任务执行成功（并发），bean={}", beanName);
        } catch (Exception e) {
            recordResult(jobId, false, e.getMessage());
            log.error("任务执行失败（并发），bean={}", beanName, e);
        }
    }

    /** 与 {@link BuddyQuartzJob} 保持一致：并发版同样回写执行结果 */
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
