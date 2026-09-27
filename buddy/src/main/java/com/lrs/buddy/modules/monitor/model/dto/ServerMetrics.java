package com.lrs.buddy.modules.monitor.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 服务器指标快照。
 */
@Data
public class ServerMetrics {

    private CpuInfo cpu;
    private MemInfo mem;
    private JvmInfo jvm;
    private List<SysFileInfo> sysFiles;
    private SysInfo sys;

    /** 采样时间戳（毫秒），前端据此判断数据新鲜度 */
    private long timestamp;

    @Data
    public static class CpuInfo {
        /** 逻辑核心数 */
        private int cpuNum;
        /** 总使用率 % */
        private double total;
        /** 系统态 % */
        private double sys;
        /** 用户态 % */
        private double user;
        /** 等待率 % */
        private double wait;
        /** 空闲率 % */
        private double free;
    }

    @Data
    public static class MemInfo {
        /** 单位 GB，保留两位小数由前端格式化 */
        private double total;
        private double used;
        private double free;
        /** 使用率 % */
        private double usage;
    }

    @Data
    public static class JvmInfo {
        private String name;
        private String version;
        private String home;
        /** 单位 MB */
        private double total;
        private double used;
        private double free;
        private double usage;
        private String startTime;
        /** 已运行时长，如 "2小时13分钟" */
        private String runTime;
    }

    @Data
    public static class SysFileInfo {
        private String dirName;
        /** 已格式化的容量字符串，如 "256.0 GB" */
        private String total;
        private String free;
        private String used;
        private double usage;
    }

    @Data
    public static class SysInfo {
        private String computerName;
        private String osName;
        private String osArch;
        private String userDir;
    }
}
