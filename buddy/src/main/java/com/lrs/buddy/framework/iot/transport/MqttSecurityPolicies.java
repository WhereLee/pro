package com.lrs.buddy.framework.iot.transport;

import com.lrs.buddy.framework.iot.repo.DeviceDirectoryDao;
import com.lrs.buddy.framework.iot.security.DeviceCredentialService;
import com.lrs.buddy.framework.iot.security.InternalClientSecrets;
import io.moquette.broker.security.IAuthenticator;
import io.moquette.broker.security.IAuthorizatorPolicy;
import io.moquette.broker.subscriptions.Topic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Broker 侧的认证与授权策略。
 *
 * 为什么授权必须落在 Broker 而不是业务层（D6）：
 * 订阅动作根本不会进业务代码。若只在业务层做归属校验，
 * 一台被攻破的柜机可以订阅别家的开仓主题并静默监听 —— 业务层根本没机会拒绝。
 */
@Slf4j
public final class MqttSecurityPolicies {

    private MqttSecurityPolicies() {
    }

    /** 云侧内部客户端：同一应用回连自己的 Broker，用于收发上下行消息。 */
    public static boolean isCloudClient(String clientId) {
        return clientId != null && clientId.startsWith(BrokerLifecycle.CLOUD_CLIENT_PREFIX);
    }

    @RequiredArgsConstructor
    public static class PasswordAuthenticator implements IAuthenticator {

        private final DeviceCredentialService credentials;
        private final InternalClientSecrets internalClientSecrets;

        @Override
        public boolean checkValid(String clientId, String username, byte[] password) {
            if (clientId == null) {
                return false;
            }
            try {
                if (isCloudClient(clientId)) {
                    return internalClientSecrets.verify(username, password == null ? null
                            : new String(password, java.nio.charset.StandardCharsets.UTF_8));
                }
                if (!clientId.contains("::")) {
                    return false;
                }
                return credentials.authenticateConnection(clientId, username, password) != null;
            } catch (RuntimeException e) {
                log.error("设备认证过程异常，按拒绝处理：{}", e.getMessage());
                return false;
            }
        }
    }

    /**
     * 主题级 ACL：设备只能读写自己 productKey/deviceId 之下的主题。
     *
     * 组播主题（dn/{pk}/group/...）在 M1 先不放行，等 M6 的分组模型落地再开，
     * 因为"谁能订阅哪个组"需要组成员关系作为依据，此刻放行等于放行全部组播。
     */
    @RequiredArgsConstructor
    public static class OwnTopicAuthorizer implements IAuthorizatorPolicy {

        private final DeviceDirectoryDao deviceDao;

        @Override
        public boolean canWrite(Topic topic, String clientId, String username) {
            return owns(topic == null ? null : topic.toString(), clientId)
                    // 设备不得往云向下行的 dn/* 里写，否则可以自己伪造"指令已下发"
                    && !topic.toString().contains("/dn/");
        }

        @Override
        public boolean canRead(Topic topic, String clientId, String username) {
            String name = topic == null ? null : topic.toString();
            if (name == null) {
                return false;
            }
            // 设备读自己的 dn/*；云侧订阅（up/#）由内部账号放行（见 isCloudClient）
            if (isCloudClient(clientId)) {
                return true;
            }
            return name.contains("/dn/") && owns(name, clientId);
        }

        private boolean owns(String topicFilter, String clientId) {
            if (topicFilter == null || clientId == null) {
                return false;
            }
            String[] segments = topicFilter.split("/");
            // 形如 swap/v1/{up|dn}/{productKey}/{deviceId}/...
            if (segments.length < 5) {
                return false;
            }
            String productKey = segments[3];
            String deviceId = segments[4];
            String ownDeviceId = clientId.substring(clientId.indexOf("::") + 2);
            String ownProductKey = clientId.substring(0, clientId.indexOf("::"));
            return ownProductKey.equals(productKey) && ownDeviceId.equals(deviceId);
        }

        private static boolean isCloudClient(String clientId) {
            return MqttSecurityPolicies.isCloudClient(clientId);
        }
    }
}
