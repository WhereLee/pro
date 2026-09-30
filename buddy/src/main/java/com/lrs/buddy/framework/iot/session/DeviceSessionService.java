package com.lrs.buddy.framework.iot.session;

import com.lrs.buddy.framework.iot.config.IotProperties;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao.Device;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话与在线状态（swap-protocol.md §2.1）。
 *
 * 在线判定不信 LWT、也不依赖 Broker 私有特性：三个来源按优先级合并 ——
 * ① 自有上行报文（权威）；② 连接关闭事件（只加速感知，不单独定罪）；③ 报文静默计时器（兜底）。
 * 并且**必须连续多轮缺失才判离线**，否则 4G 网络抖一下就会把整片柜机标成离线，
 * 进而让所有下单 guard 拒绝、告警风暴、运维工单爆量。
 *
 * "疑似离线"(STALE) 与"确认离线"(OFFLINE) 是两个不同状态，不能合并成一个布尔。
 */
@Slf4j
@RequiredArgsConstructor
public class DeviceSessionService {

    private final DeviceDirectoryDao deviceDao;
    private final IotProperties properties;

    /** 设备 → 连续静默判定轮数（内存态，重启后由 last_seen_ts 重算，不影响正确性）。 */
    private final Map<Long, Integer> silentRounds = new ConcurrentHashMap<>();

    public String onConnect(Device device, String clientId, String nodeId) {
        String sessionId = "s-" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        if (device == null) {
            return sessionId;
        }
        deviceDao.openSession(device.id(), sessionId, nodeId, properties.getHeartbeatSecondsOf(device),
                0, now, device.tenantId());
        deviceDao.updateOnlineState(device.id(), "ONLINE", toMillis(now), now, null);
        silentRounds.remove(device.id());
        return sessionId;
    }

    public void onDisconnect(Device device, String sessionId, String reason) {
        if (device == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        deviceDao.closeSession(sessionId, reason, now);
        // 关闭事件只把状态推到 STALE：真实在线与否仍由报文静默计时器判定
        deviceDao.updateOnlineState(device.id(), "STALE", null, now, reason);
        log.debug("设备连接结束：deviceId={}, sessionId={}, reason={}", device.deviceId(), sessionId, reason);
    }

    /** 任何一条合法上行报文都算一次心跳证据。 */
    public void markSeen(Device device) {
        if (device == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        deviceDao.updateOnlineState(device.id(), "ONLINE", toMillis(now), now, null);
        silentRounds.remove(device.id());
    }

    /**
     * 兜底扫描：把"长时间没有报文"的设备从 ONLINE/STALE 推到 OFFLINE。
     *
     * 不依赖 Broker 的遗嘱或连接事件 —— 那些只能加速，不能作为唯一真相。
     */
    @Scheduled(fixedDelayString = "${buddy.iot.online-scan-interval-ms:10000}")
    @SchedulerLock(name = "iot-online-scan", lockAtMostFor = "PT30S", lockAtLeastFor = "PT5S")
    public void scanOnlineState() {
        if (!properties.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<Long, Integer> rounds = new HashMap<>(silentRounds);
        for (Device device : deviceDao.devicesNotOffline()) {
            long heartbeatMillis = Math.max(30, properties.getHeartbeatSecondsOf(device)) * 1000L;
            long threshold = (long) Math.ceil(heartbeatMillis * properties.getOfflineFactor());
            Long lastSeen = device.lastSeenTs();
            boolean silent = lastSeen == null || now - lastSeen > threshold;
            if (!silent) {
                rounds.remove(device.id());
                continue;
            }
            int round = rounds.merge(device.id(), 1, Integer::sum);
            if (round >= properties.getOfflineConfirmRounds()) {
                deviceDao.updateOnlineState(device.id(), "OFFLINE", lastSeen, LocalDateTime.now(),
                        "SILENT_" + round + "_ROUNDS");
                rounds.remove(device.id());
            } else {
                deviceDao.updateOnlineState(device.id(), "STALE", lastSeen, LocalDateTime.now(), null);
            }
        }
        silentRounds.clear();
        silentRounds.putAll(rounds);
    }

    private static long toMillis(LocalDateTime time) {
        return time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
