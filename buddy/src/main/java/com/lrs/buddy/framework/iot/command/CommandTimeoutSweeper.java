package com.lrs.buddy.framework.iot.command;

import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.repo.CommandDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 指令超时兜底扫描（协议 §7.4 双保险的第二条）。
 *
 * 为什么必须有它：内存延迟任务在发布重启、进程崩溃、OOM 被杀时全部丢失。
 * 只靠内存任务的结果是"一批指令永久挂在 DISPATCHED"，
 * 而且没有任何报错 —— 它表现为"柜机没响应"，排查方向会被带到网络和固件上去。
 *
 * 两条路径同时命中同一条指令时必须只生效一次：靠带 fromState 谓词的 CAS 迁移实现，
 * 所以 handleTimeout 可以安全重入。
 */
@Slf4j
@RequiredArgsConstructor
public class CommandTimeoutSweeper {

    private final CommandDao commandDao;
    private final DeviceCommandService commands;
    private final IotProperties properties;

    @Scheduled(fixedDelayString = "${buddy.iot.command-scan-interval-ms:10000}")
    @SchedulerLock(name = "iot-command-timeout", lockAtMostFor = "PT30S", lockAtLeastFor = "PT2S")
    public void sweep() {
        if (!properties.isEnabled()) {
            return;
        }
        for (CommandDao.Row row : commandDao.scanDue(System.currentTimeMillis(), 200)) {
            try {
                commands.handleTimeout(row.id(), row.cmdId());
            } catch (RuntimeException e) {
                log.error("指令超时兜底处理异常：cmdId={}, err={}", row.cmdId(), e.getMessage(), e);
            }
        }
    }
}
