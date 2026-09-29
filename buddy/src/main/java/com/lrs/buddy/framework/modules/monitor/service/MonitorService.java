package com.lrs.buddy.framework.modules.monitor.service;

import com.lrs.buddy.framework.common.sse.SseEmitterManager;
import com.lrs.buddy.framework.modules.monitor.model.dto.ServerMetrics;
import lombok.extern.slf4j.Slf4j;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 服务器指标采集。
 *
 * <h3>为什么要"后台定时采样 + 缓存"，而不是接口被调用时才读取</h3>
 * <ol>
 *   <li><b>CPU 使用率必须两次采样求差</b>：oshi 读取的是自开机以来的累计 tick 计数，
 *       单次读取只能得到历史平均值，必须间隔一段时间读两次取差值才是"当前使用率"。
 *       如果放在接口里做，每个请求都要阻塞几百毫秒。</li>
 *   <li><b>避免前端轮询放大读系统调用</b>：多个页面同时打开时，
 *       每次轮询都会触发一次全量采集（含磁盘遍历，代价不小）。
 *       统一在后台按固定频率采集一次，前端无论多少都只是读内存快照。</li>
 * </ol>
 */
@Slf4j
@Service
public class MonitorService {

    /** oshi 的入口对象，线程安全，创建一次复用即可 */
    private final SystemInfo systemInfo = new SystemInfo();
    private final HardwareAbstractionLayer hardware = systemInfo.getHardware();
    private final OperatingSystem operatingSystem = systemInfo.getOperatingSystem();

    private final AtomicReference<ServerMetrics> latest = new AtomicReference<>();
    private final SseEmitterManager sseEmitterManager;

    /** CPU 两次采样的间隔：太短误差大，太长响应慢，500ms 是常用取值 */
    private static final long CPU_SAMPLE_INTERVAL_MS = 500L;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public MonitorService(SseEmitterManager sseEmitterManager) {
        this.sseEmitterManager = sseEmitterManager;
        // 启动即采集一次，避免第一个打开页面的用户看到空数据
        sample();
    }

    /**
     * 定时采集。fixedDelay 表示上一次结束后 3 秒再执行，
     * 与 fixedRate 的区别是不会因单次耗时过长而堆积任务。
     */
    @Scheduled(fixedDelayString = "${buddy.monitor.sample-interval-ms:3000}")
    public void sample() {
        try {
            ServerMetrics metrics = collect();
            latest.set(metrics);
            sseEmitterManager.broadcast("monitor-server", metrics);
        } catch (Exception e) {
            // 采集失败不应影响定时任务继续调度，只记录日志
            log.error("服务器指标采集失败：{}", e.getMessage(), e);
        }
    }

    /** SSE 心跳，保活连接 */
    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        sseEmitterManager.heartbeat();
    }

    /**
     * 获取最新快照；若尚未采集过则立即采集一次。
     */
    public ServerMetrics snapshot() {
        ServerMetrics metrics = latest.get();
        return metrics != null ? metrics : collect();
    }

    private ServerMetrics collect() {
        ServerMetrics metrics = new ServerMetrics();
        metrics.setCpu(collectCpu());
        metrics.setMem(collectMem());
        metrics.setJvm(collectJvm());
        metrics.setSysFiles(collectDisk());
        metrics.setSys(collectSys());
        metrics.setTimestamp(System.currentTimeMillis());
        return metrics;
    }

    private ServerMetrics.CpuInfo collectCpu() {
        CentralProcessor processor = hardware.getProcessor();
        long[] prevTicks = processor.getSystemCpuLoadTicks();
        try {
            Thread.sleep(CPU_SAMPLE_INTERVAL_MS);
        } catch (InterruptedException e) {
            // 恢复中断标志，让上层调度器能感知；本次退化为返回 0，下次采样自然修正
            Thread.currentThread().interrupt();
            ServerMetrics.CpuInfo empty = new ServerMetrics.CpuInfo();
            empty.setCpuNum(processor.getLogicalProcessorCount());
            empty.setTotal(0);
            empty.setSys(0);
            empty.setUser(0);
            empty.setWait(0);
            empty.setFree(100);
            return empty;
        }
        long[] ticks = processor.getSystemCpuLoadTicks();

        // TickType 顺序：USER, NICE, SYSTEM, IDLE, IOWAIT, IRQ, SOFTIRQ, STEAL
        long user = ticks[0] - prevTicks[0];
        long nice = ticks[1] - prevTicks[1];
        long sys = ticks[2] - prevTicks[2];
        long idle = ticks[3] - prevTicks[3];
        long iowait = ticks[4] - prevTicks[4];
        long irq = ticks[5] - prevTicks[5];
        long softirq = ticks[6] - prevTicks[6];
        long steal = ticks[7] - prevTicks[7];
        long totalCpu = user + nice + sys + idle + iowait + irq + softirq + steal;

        ServerMetrics.CpuInfo cpu = new ServerMetrics.CpuInfo();
        cpu.setCpuNum(processor.getLogicalProcessorCount());
        if (totalCpu <= 0) {
            // 极端情况下（采样间隔内没有任何 tick 变化）避免除零
            cpu.setTotal(0);
            cpu.setSys(0);
            cpu.setUser(0);
            cpu.setWait(0);
            cpu.setFree(100);
            return cpu;
        }
        cpu.setSys(round(100d * sys / totalCpu));
        cpu.setUser(round(100d * (user + nice) / totalCpu));
        cpu.setWait(round(100d * (iowait + irq + softirq) / totalCpu));
        cpu.setFree(round(100d * idle / totalCpu));
        // 总使用率 = 100 - 空闲，包含 steal 等其它占用
        cpu.setTotal(round(100d - 100d * idle / totalCpu));
        return cpu;
    }

    private ServerMetrics.MemInfo collectMem() {
        GlobalMemory memory = hardware.getMemory();
        double total = memory.getTotal() / 1024.0 / 1024.0 / 1024.0;
        // 注意用 getAvailable（真正可被程序使用的量），而不是 getTotal - getUsed，
        // 后者会把 Linux 的 page cache 误算成"已使用"
        double available = memory.getAvailable() / 1024.0 / 1024.0 / 1024.0;
        double used = total - available;

        ServerMetrics.MemInfo mem = new ServerMetrics.MemInfo();
        mem.setTotal(round(total));
        mem.setUsed(round(used));
        mem.setFree(round(available));
        mem.setUsage(total > 0 ? round(used / total * 100) : 0);
        return mem;
    }

    private ServerMetrics.JvmInfo collectJvm() {
        Runtime runtime = Runtime.getRuntime();
        double total = runtime.totalMemory() / 1024.0 / 1024.0;
        double free = runtime.freeMemory() / 1024.0 / 1024.0;
        double used = total - free;

        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        long uptimeMillis = runtimeMXBean.getUptime();

        ServerMetrics.JvmInfo jvm = new ServerMetrics.JvmInfo();
        jvm.setName(runtimeMXBean.getVmName());
        jvm.setVersion(System.getProperty("java.version"));
        jvm.setHome(System.getProperty("java.home"));
        jvm.setTotal(round(total));
        jvm.setUsed(round(used));
        jvm.setFree(round(free));
        jvm.setUsage(total > 0 ? round(used / total * 100) : 0);
        jvm.setStartTime(LocalDateTime.now().minusSeconds(uptimeMillis / 1000).format(TIME_FORMATTER));
        jvm.setRunTime(formatUptime(uptimeMillis));
        return jvm;
    }

    private List<ServerMetrics.SysFileInfo> collectDisk() {
        FileSystem fileSystem = operatingSystem.getFileSystem();
        List<ServerMetrics.SysFileInfo> files = new ArrayList<>();
        for (OSFileStore store : fileSystem.getFileStores()) {
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            if (total <= 0) {
                // 光驱、虚拟文件系统等没有容量的设备，跳过
                continue;
            }
            ServerMetrics.SysFileInfo info = new ServerMetrics.SysFileInfo();
            info.setDirName(store.getMount());
            info.setTotal(formatBytes(total));
            info.setFree(formatBytes(usable));
            info.setUsed(formatBytes(total - usable));
            info.setUsage(round((double) (total - usable) / total * 100));
            files.add(info);
        }
        return files;
    }

    private ServerMetrics.SysInfo collectSys() {
        ServerMetrics.SysInfo sys = new ServerMetrics.SysInfo();
        String hostName = System.getenv("COMPUTERNAME");
        if (hostName == null || hostName.isBlank()) {
            hostName = System.getenv("HOSTNAME");
        }
        sys.setComputerName(hostName == null ? "unknown" : hostName);
        sys.setOsName(operatingSystem.getFamily() + " " + operatingSystem.getVersionInfo());
        sys.setOsArch(System.getProperty("os.arch"));
        sys.setUserDir(System.getProperty("user.dir"));
        return sys;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 字节转人类可读容量 */
    private static String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        int unitIndex = (int) (Math.log10(bytes) / Math.log10(1024));
        unitIndex = Math.min(unitIndex, units.length - 1);
        double value = bytes / Math.pow(1024, unitIndex);
        return String.format("%.1f %s", value, units[unitIndex]);
    }

    /** 毫秒转 "x天x小时x分钟" */
    private static String formatUptime(long millis) {
        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天");
        }
        if (hours > 0) {
            sb.append(hours).append("小时");
        }
        sb.append(minutes).append("分钟");
        return sb.toString();
    }
}
