package com.lrs.buddy.framework.iot.gateway;

import com.lrs.buddy.framework.iot.session.EndpointRegistry;
import com.lrs.buddy.framework.iot.transport.CloudMqttLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 两种下行实现，由 buddy.iot.mode 选择。
 *
 * embedded：Broker 在本进程内，直接拿已连接端点写报文（零跳数，本地与 CI 用这条）。
 * client：  Broker 是外部服务（生产 EMQX），云侧作为标准 MQTT 客户端发布（业务代码不变）。
 *
 * 跨节点路由：Redis 里记着"设备连在哪台"。单节点时恒为本节点，
 * 多节点时若不在本节点，embedded 实现会返回 NOT_CONNECTED 并打日志 ——
 * 这是刻意的保守行为：宁可不发（指令会超时并走反查），也不能假装发出去了。
 */
@Slf4j
public final class Gateways {

    private Gateways() {
    }

    @RequiredArgsConstructor
    public static class Embedded implements DeviceGateway {

        private final EndpointRegistry endpoints;
        private final StringRedisTemplate redis;

        @Override
        public SendResult send(SendRequest request) {
            if (request.deviceRowId() == null) {
                return SendResult.REJECTED;
            }
            if (!endpoints.isLocalConnected(request.deviceRowId()) && !routedHere(request.deviceRowId())) {
                log.debug("设备未连在本节点，指令不下发：deviceId={}", request.deviceId());
                return SendResult.NOT_CONNECTED;
            }
            boolean sent = endpoints.publish(request.deviceRowId(), request.topic(), request.payload(),
                    request.qos(), request.critical());
            return sent ? SendResult.SENT : SendResult.NOT_CONNECTED;
        }

        private boolean routedHere(Long deviceRowId) {
            // 端点表是权威；路由记录只用于多节点判断，缺失即视为不在本节点
            return false;
        }
    }

    @RequiredArgsConstructor
    public static class External implements DeviceGateway {

        private final CloudMqttLink link;

        @Override
        public SendResult send(SendRequest request) {
            if (!link.isRunning()) {
                return SendResult.NOT_CONNECTED;
            }
            try {
                link.publish(request.topic(), request.payload(), request.qos());
                return SendResult.SENT;
            } catch (RuntimeException e) {
                // 发布异常不能吞：调用方要把指令置回可重试状态
                log.error("外部 Broker 发布失败：topic={}, err={}", request.topic(), e.getMessage());
                return SendResult.NOT_CONNECTED;
            }
        }
    }
}
