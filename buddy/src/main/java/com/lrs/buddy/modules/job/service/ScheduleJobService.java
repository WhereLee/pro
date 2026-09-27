package com.lrs.buddy.modules.job.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lrs.buddy.common.BusinessException;
import com.lrs.buddy.common.util.SpringContextUtils;
import com.lrs.buddy.modules.job.BuddyQuartzJob;
import com.lrs.buddy.modules.job.ConcurrentQuartzJob;
import com.lrs.buddy.modules.job.entity.SysJob;
import com.lrs.buddy.modules.job.mapper.SysJobMapper;
import com.lrs.buddy.modules.job.task.ITask;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 定时任务管理。
 *
 * <h3>为什么任务定义要存自己的表</h3>
 * Quartz 自带的 QRTZ_ 表是它的内部存储，字段多为缩写与二进制，
 * 业务侧要查询、展示、关联业务数据都很别扭。
 * 因此这里让 {@code sys_job} 承担"业务语义"（名称、分组、参数、状态），
 * Quartz 只负责"到点触发"。
 *
 * <h3>为什么启动时重新注册</h3>
 * 当前使用内存型 JobStore，进程重启后 Quartz 里的任务就消失了。
 * 所以启动时读一次 {@code sys_job}，把"正常"状态的任务重新注册回去，
 * 达到"重启后任务自动恢复"的效果。
 * 若将来要多实例部署，把 Quartz 切到 JDBC JobStore（集群模式）即可，
 * 业务代码不需要改动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleJobService extends ServiceImpl<SysJobMapper, SysJob> {

    private static final String JOB_GROUP_PREFIX = "BUDDY_JOB_";

    private final Scheduler scheduler;

    /** 状态：0 正常，1 暂停 */
    private static final int STATUS_NORMAL = 0;
    private static final int STATUS_PAUSED = 1;

    /**
     * 启动加载。
     *
     * <p>放在 {@code @PostConstruct} 而不是 {@code CommandLineRunner}：
     * 任务注册应该在应用就绪前完成，避免"接口已可访问但任务还没起来"的窗口期。
     */
    @PostConstruct
    public void initJobs() {
        List<SysJob> jobs = list();
        int registered = 0;
        for (SysJob job : jobs) {
            if (STATUS_NORMAL == job.getStatus()) {
                try {
                    registerJob(job);
                    registered++;
                } catch (Exception e) {
                    log.error("启动注册任务失败，jobId={}，cron={}：{}",
                            job.getId(), job.getCronExpression(), e.getMessage());
                }
            }
        }
        log.info("定时任务初始化完成，共 {} 个，已注册 {} 个", jobs.size(), registered);
    }

    @Transactional(rollbackFor = Exception.class)
    public void createJob(SysJob job) {
        validateCron(job.getCronExpression());
        job.setStatus(STATUS_NORMAL);
        save(job);
        try {
            registerJob(job);
        } catch (SchedulerException e) {
            // 注册失败要回滚数据库记录，否则会留下"表里有、调度器里没有"的幽灵任务
            throw new BusinessException("任务注册失败：" + e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateJob(SysJob job) {
        SysJob exist = getById(job.getId());
        if (exist == null) {
            throw new BusinessException("任务不存在");
        }
        validateCron(job.getCronExpression());
        updateById(job);

        // 先移除旧的再按新配置注册：改了 cron 之后原触发器不会自动更新
        unregisterJob(exist);
        if (STATUS_NORMAL == job.getStatus()) {
            try {
                registerJob(job);
            } catch (SchedulerException e) {
                throw new BusinessException("任务重新注册失败：" + e.getMessage());
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteJobs(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 雪花 ID 是 19 位超大整数：用字符串接收可同时规避 Jackson 反序列化溢出
        // 与前端 JS 的 2^53 精度丢失问题，避免"删错/删不到"的隐患
        List<Long> longIds = ids.stream().map(Long::valueOf).toList();
        for (Long id : longIds) {
            SysJob job = getById(id);
            if (job != null) {
                unregisterJob(job);
            }
        }
        removeByIds(longIds);
    }

    /** 暂停：数据库置为暂停，并移除调度（否则重启后仍会被加载） */
    @Transactional(rollbackFor = Exception.class)
    public void pause(Long jobId) {
        SysJob job = getById(jobId);
        if (job == null) {
            throw new BusinessException("任务不存在");
        }
        job.setStatus(STATUS_PAUSED);
        updateById(job);
        unregisterJob(job);
    }

    @Transactional(rollbackFor = Exception.class)
    public void resume(Long jobId) {
        SysJob job = getById(jobId);
        if (job == null) {
            throw new BusinessException("任务不存在");
        }
        job.setStatus(STATUS_NORMAL);
        updateById(job);
        try {
            registerJob(job);
        } catch (SchedulerException e) {
            throw new BusinessException("任务恢复失败：" + e.getMessage());
        }
    }

    /**
     * 立即执行一次（不影响后续调度计划）。
     */
    public void runOnce(Long jobId) {
        SysJob job = getById(jobId);
        if (job == null) {
            throw new BusinessException("任务不存在");
        }
        try {
            scheduler.triggerJob(jobKey(job));
        } catch (SchedulerException e) {
            throw new BusinessException("触发任务失败：" + e.getMessage());
        }
    }

    /**
     * 记录本次执行结果，供任务列表直接看到"上次跑没跑成功"。
     *
     * <p>由任务执行器在每次执行结束后回调。这里不抛异常：
     * 结果记录属于旁路信息，失败只写日志，不影响任务本身。
     */
    public void recordResult(Long jobId, boolean success, String message) {
        if (jobId == null) {
            return;
        }
        try {
            SysJob update = new SysJob();
            update.setId(jobId);
            update.setLastStatus(success ? 0 : 1);
            update.setLastMessage(message == null ? null : message.substring(0, Math.min(message.length(), 500)));
            updateById(update);
        } catch (Exception e) {
            log.warn("记录任务执行结果失败，jobId={}：{}", jobId, e.getMessage());
        }
    }

    /**
     * 按配置的策略处理"错过了触发时间"的情况。
     *
     * <p>misfire 指调度器在应该触发时没触发（应用停机、线程池耗尽等）。
     * 不同业务对补偿的期望不同，所以交给任务自己配置：
     * <ul>
     *   <li>1 立即补执行一次</li>
     *   <li>2 忽略错过的次数，按原计划继续</li>
     *   <li>3 放弃错过的，等下一次（默认，最保守）</li>
     * </ul>
     */
    private CronScheduleBuilder applyMisfirePolicy(CronScheduleBuilder builder, Integer policy) {
        int p = policy == null ? 3 : policy;
        return switch (p) {
            case 1 -> builder.withMisfireHandlingInstructionFireAndProceed();
            case 2 -> builder.withMisfireHandlingInstructionIgnoreMisfires();
            default -> builder.withMisfireHandlingInstructionDoNothing();
        };
    }

    /**
     * 列出容器内所有 {@link ITask} 实现类的 Bean 名称，
     * 供前端新增任务时下拉选择，避免手工输入 bean 名出错。
     */
    public List<String> taskBeanNames() {
        return SpringContextUtils.context()
                .getBeansOfType(ITask.class)
                .keySet()
                .stream()
                .sorted()
                .toList();
    }

    /* ================= 私有方法 ================= */

    private void registerJob(SysJob job) throws SchedulerException {
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(BuddyQuartzJob.KEY_BEAN_NAME, job.getBeanName());
        dataMap.put(BuddyQuartzJob.KEY_PARAMS, job.getParams() == null ? "" : job.getParams());
        // jobId 传给执行器，用于回写本次执行结果
        dataMap.put(BuddyQuartzJob.KEY_JOB_ID, job.getId());

        // 是否允许并发决定了用哪个 Job 类
        Class<? extends org.quartz.Job> jobClass =
                Integer.valueOf(0).equals(job.getConcurrent())
                        ? ConcurrentQuartzJob.class
                        : BuddyQuartzJob.class;

        JobDetail jobDetail = JobBuilder.newJob(jobClass)
                .withIdentity(jobKey(job))
                .withDescription(job.getJobName())
                .setJobData(dataMap)
                .build();

        // cron 表达式不合法时 CronScheduleBuilder 会直接抛异常，
        // 属于"快速失败"，好过静默变成永不执行的任务
        CronScheduleBuilder scheduleBuilder =
                applyMisfirePolicy(CronScheduleBuilder.cronSchedule(job.getCronExpression()),
                        job.getMisfirePolicy());

        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey(job))
                .withSchedule(scheduleBuilder)
                .build();

        if (scheduler.checkExists(jobKey(job))) {
            scheduler.deleteJob(jobKey(job));
        }
        scheduler.scheduleJob(jobDetail, trigger);
        log.info("任务已注册，jobId={}, cron={}", job.getId(), job.getCronExpression());
    }

    private void unregisterJob(SysJob job) {
        try {
            scheduler.pauseTrigger(triggerKey(job));
            scheduler.unscheduleJob(triggerKey(job));
            scheduler.deleteJob(jobKey(job));
        } catch (SchedulerException e) {
            // 任务本来就不存在时删除会抛异常，这里只记日志，不打断主流程
            log.debug("移除任务失败（可能本就不存在），jobId={}：{}", job.getId(), e.getMessage());
        }
    }

    private void validateCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new BusinessException("cron 表达式不能为空");
        }
        try {
            CronScheduleBuilder.cronSchedule(cron);
        } catch (RuntimeException e) {
            throw new BusinessException("cron 表达式不合法：" + cron);
        }
    }

    private JobKey jobKey(SysJob job) {
        return JobKey.jobKey(JOB_GROUP_PREFIX + job.getId(), job.getJobGroup());
    }

    private TriggerKey triggerKey(SysJob job) {
        return TriggerKey.triggerKey(JOB_GROUP_PREFIX + job.getId(), job.getJobGroup());
    }
}
