package com.lrs.buddy.framework.modules.job.task;

/**
 * 定时任务执行体。
 *
 * <p>实现类注册为 Spring Bean，在任务配置里通过 bean 名称引用即可，
 * 不需要在代码里手写反射调用字符串——后者一旦类名或方法名改动就会在运行时才暴露错误。
 */
public interface ITask {

    /**
     * 执行任务。
     *
     * @param params 任务参数，来自任务配置
     */
    void run(String params);
}
