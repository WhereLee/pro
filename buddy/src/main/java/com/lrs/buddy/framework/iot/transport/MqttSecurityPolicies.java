package com.lrs.buddy.framework.iot.transport;

import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.security.DeviceCredentialService;
import com.lrs.buddy.framework.iot.security.InternalClientSecrets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * Broker 侧的认证与授权判定（与具体 Broker 实现无关，Vert.x / 外部 Broker 共用）。
 *
 * 为什么授权必须落在连接层而不是业务层（D6）：
 * 订阅动作根本不会进业务代码。若只在业务层做归属校验，
 * 一台被攻破的柜机可以订阅别家的开仓主题并静默监听 —— 业务层根本没机会拒绝。
 */
@Slf4j
@RequiredArgsConstructor
public class MqttSecurityPolicies {

    private final DeviceCredentialService credentials;
    private final InternalClientSecrets internalClientSecrets;
    private final DeviceDirectoryDao deviceDao;

    /** 云侧内部客户端：同一应用回连自身 Broker（外部 Broker 模式）。 */
    public static boolean isCloudClient(String clientId) {
        return clientId != null && clientId.startsWith(BrokerLifecycle.CLOUD_CLIENT_PREFIX);
    }

    /**
     * 连接认证。失败只计数不写明细，避免爆破流量变成写放大（D5）。
     *
     * @return 通过时返回设备（内部客户端返回 null 但 allowed=true）
     */
    public AuthResult authenticate(String clientId, String username, String password) {
        if (clientId == null) {
            return AuthResult.deny();
        }
        try {
            if (isCloudClient(clientId)) {
                return internalClientSecrets.verify(username, password) ? AuthResult.allowInternal() : AuthResult.deny();
            }
            if (!clientId.contains("::")) {
                return AuthResult.deny();
            }
            var device = credentials.authenticateConnection(clientId, username,
                    password == null ? new byte[0] : password.getBytes(StandardCharsets.UTF_8));
            return device == null ? AuthResult.deny() : AuthResult.allowDevice(device);
        } catch (RuntimeException e) {
            log.error("认证过程异常，按拒绝处理：{}", e.getMessage());
            return AuthResult.deny();
        }
    }

    /**
     * 主题读写授权。
     *
     * 设备不得往 dn/* 写（否则可以自己伪造"指令已下发"），也不得读别人的 dn/*；
     * 组播主题（dn/{pk}/group/...）在 M1 一律不放行，等 M6 分组模型落地再开
     * —— 此刻放行等于放行全部组播。
     */
    public boolean canRead(String topicFilter, String clientId) {
        if (topicFilter == null || clientId == null) {
            return false;
        }
        if (isCloudClient(clientId)) {
            return true;
        }
        return topicFilter.contains("/dn/") && owns(topicFilter, clientId);
    }

    public boolean canWrite(String topicName, String clientId) {
        if (topicName == null || clientId == null || isCloudClient(clientId)) {
            return false;
        }
        // 云侧订阅 up/#，设备只写自己的 up/*
        return topicName.contains("/up/") && owns(topicName, clientId);
    }

    private boolean owns(String topic, String clientId) {
        String[] segments = topic.split("/");
        // 形如 swap/v1/{up|dn}/{productKey}/{deviceId}/...
        if (segments.length < 5) {
            return false;
        }
        int idx = clientId.indexOf("::");
        if (idx <= 0) {
            return false;
        }
        return clientId.substring(0, idx).equals(segments[3]) && clientId.substring(idx + 2).equals(segments[4]);
    }

    /** 认证结果：设备客户端携带设备行，内部客户端不建假设备。 */
    public record AuthResult(boolean allowed, DeviceDirectoryDao.Device device, boolean internal) {

        public static AuthResult deny() {
            return new AuthResult(false, null, false);
        }

        public static AuthResult allowDevice(DeviceDirectoryDao.Device device) {
            return new AuthResult(true, device, false);
        }

        public static AuthResult allowInternal() {
            return new AuthResult(true, null, true);
        }
    }
}
