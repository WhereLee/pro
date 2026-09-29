package com.lrs.buddy.framework.iot.envelope;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 设备报文统一信封（swap-protocol.md §3）。
 *
 * 字段不是装饰，每一项都对应一个真实失效模式：
 * msgId 应对 QoS1 必然重复；issuedAt/expireAt 承载有效期且不依赖设备时钟可信；
 * nonce 防重放；sessionId 是跨会话迟到报文的丢弃依据（防串单）；
 * seq 让乱序可被观测；traceId 贯穿 HTTP 到 MQTT 再到应答。
 *
 * @param v         协议版本，如 1.0
 * @param msgId     ULID，全局唯一
 * @param issuedAt  发送方毫秒时间戳（诊断用）
 * @param expireAt  绝对过期毫秒时间戳（云侧按下发时刻 + ttl 计算，被签名覆盖）
 * @param nonce     随机串
 * @param traceId   链路号
 * @param sessionId 报文所属连接会话
 * @param from      实际发布方 productKey::deviceId
 * @param via       子设备透传时的网关标识
 * @param seq       每设备单调序号
 * @param cmd       指令标识；上行事件用 data.eventType
 * @param code      应答错误码，成功为 OK 或 null
 * @param data      业务载荷
 * @param sign      报文签名
 */
public record Envelope(
        String v,
        String msgId,
        Long issuedAt,
        Long expireAt,
        String nonce,
        String traceId,
        String sessionId,
        String from,
        String via,
        Long seq,
        String cmd,
        String code,
        JsonNode data,
        String sign) {

    public boolean expiredAt(long nowMillis) {
        return expireAt != null && nowMillis > expireAt;
    }

    /** 应答类报文判定：code 非空即视为对某条指令的回应。 */
    public boolean isReply() {
        return code != null && !code.isBlank();
    }
}
