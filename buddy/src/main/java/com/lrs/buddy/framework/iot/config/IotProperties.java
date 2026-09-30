package com.lrs.buddy.framework.iot.config;

import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 设备接入层配置（buddy.iot.*）。
 *
 * 两个默认值反映的是设计立场，不是随手填的：
 * enabled 默认 true 但 broker 端口默认只绑 127.0.0.1 —— 开发期暴露面最小；
 * recordPayload 默认为真 —— 排障期需要原始报文可回放，代价由 recordMaxPayloadBytes 上限兜住。
 */
@Data
@Component
@ConfigurationProperties(prefix = "buddy.iot")
public class IotProperties {

    /** 是否启用设备接入层（关闭时不启动 broker，便于纯业务测试）。 */
    private boolean enabled = true;

    private String host = "127.0.0.1";

    private int port = 1883;

    /** TLS 端口；证书未配置时不启用。 */
    private int sslPort = 8883;

    private String keyStorePath;

    private String keyStorePassword;

    /** 节点标识：连接注册表用它区分"设备连在哪台"（swap-protocol.md §7.5）。 */
    private String nodeId = "node-1";

    /** 云侧订阅上行消息的主题过滤器；生产可改为 $share 共享订阅以横向扩容。 */
    private String subscribeFilter = "swap/v1/up/#";

    /** 设备主密钥（base64，16/24/32 字节）。缺失时所有签名校验按失败处理。 */
    private String deviceSecretKey;

    /**
     * 接入模式：{@code embedded}=本进程内起 Broker（本地/CI）；{@code client}=接外部 Broker（生产 EMQX）。
     * 两者只换传输实现，协议与业务代码不变。
     */
    private String mode = "embedded";

    public boolean isClientMode() {
        return "client".equalsIgnoreCase(mode);
    }

    /** 云侧内部接入口令（同一个应用既是 Broker 宿主又是 MQTT 客户端）。
     * 缺失时禁止内部客户端连入 —— 宁可接入层不可用，也不留一个默认口令的后门。
     */
    private String internalSecret;

    /** 连接时间戳允许偏移（秒），同时决定 nonce 的重放窗口。 */
    private long authClockWindowSeconds = 300;

    /** 业务处理线程池：接收线程绝不碰数据库（D3）。 */
    private int ingestPoolSize = 8;

    private int ingestQueueCapacity = 4096;

    /** 留痕写入线程池与队列上限；队列满即丢弃并计数，绝不反压接收线程。 */
    private int recordPoolSize = 4;

    private int recordQueueCapacity = 8192;

    /** 落库的 payload 字节上限，超出按截断标记。 */
    private int recordMaxPayloadBytes = 8192;

    /** 去重记录保留天数。 */
    private int dedupRetentionDays = 7;

    /** 遥测批量落库大小与拉取上限。 */
    private int telemetryBatchSize = 200;

    private int telemetryFlushIntervalMs = 1000;

    /** 遥测分区保留天数（M1 的分区滚动 Job 使用）。 */
    private int telemetryRetentionDays = 30;

    /** 在线状态扫描周期与判定阈值（协议 §2.1：1.5 倍心跳 + 连续两次）。 */
    private long onlineScanIntervalMs = 10000;

    private double offlineFactor = 1.5;

    private int offlineConfirmRounds = 2;

    /** 指令相关：默认 ttl 与兜底扫描周期（协议 §4.1、§7.4）。 */
    private int commandDefaultTtlSeconds = 60;

    private long commandScanIntervalMs = 10000;

    /** 默认心跳间隔（秒），用于未接入物模型/品类时的兼底。 */
    private int defaultHeartbeatSeconds = 120;

    /** 设备心跳间隔：取品类默认值，缺失则用全局兼底。 */
    public int getHeartbeatSecondsOf(DeviceDirectoryDao.Device device) {
        return defaultHeartbeatSeconds;
    }

    public byte[] decodedSecretKeyBytes() {
        if (deviceSecretKey == null || deviceSecretKey.isBlank()) {
            return null;
        }
        return java.util.Base64.getDecoder().decode(deviceSecretKey);
    }

    /** AES 主密钥（base64 原文，CryptoUtil 自行解码校验长度）。 */
    public String secretKeyBytes() {
        return deviceSecretKey;
    }
}
