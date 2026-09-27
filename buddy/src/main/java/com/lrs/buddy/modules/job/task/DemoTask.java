package com.lrs.buddy.modules.job.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 示例任务：验证调度链路是否可用。
 *
 * <p>Bean 名称（{@code demoTask}）就是在任务配置里填的"执行体"。
 * 真实业务任务照此实现 {@link ITask} 并注册为 Bean 即可，
 * 可以正常注入 Service、使用事务，与业务代码完全一致。
 */
@Slf4j
@Component("demoTask")
public class DemoTask implements ITask {

    @Override
    public void run(String params) {
        log.info("【示例任务】执行成功，参数={}，时间={}", params, java.time.LocalDateTime.now());
    }
}
