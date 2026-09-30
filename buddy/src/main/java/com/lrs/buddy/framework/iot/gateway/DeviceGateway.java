package com.lrs.buddy.framework.iot.gateway;

/**
 * 设备下行通道端口。
 *
 * 为什么要一层端口：Broker 可换（嵌入式 Vert.x ↔ 生产 EMQX），
 * 但指令总线不该跟着换。端口只暴露"发一条消息，告诉我是已发送、设备离线、还是本节点没有这条连接"。
 *
 * 注意 send 的返回值不是 boolean：{@code NOT_CONNECTED} 与 {@code REJECTED} 的后续处理完全不同 ——
 * 前者可以等设备上线，后者说明权限或参数有错，重试永远不会有结果。
 */
public interface DeviceGateway {

    enum SendResult {
        /** 已交给 Broker（不代表设备收到，更不代表执行） */
        SENT,
        /** 设备当前没有可用连接 */
        NOT_CONNECTED,
        /** 被授权或参数判定拒绝 */
        REJECTED
    }

    SendResult send(SendRequest request);

    /** 下行请求。 */
    record SendRequest(Long deviceRowId, String productKey, String deviceId, String topic, byte[] payload,
                       int qos, boolean critical, String sessionId) {

        public boolean isCritical() {
            return critical;
        }
    }
}
