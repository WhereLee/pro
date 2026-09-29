package com.lrs.buddy.framework.iot.envelope;

/**
 * 报文编解码 SPI。
 *
 * 存在理由（swap-plan.md §7 A1）：M1 只落 JSON 实现，M8 要加二进制帧实现，
 * 两者必须能通过同一套 golden 样本，否则说明 SPI 被 JSON 焊死了。
 * 因此解码不得假设"字段顺序"或"可选字段一定存在"。
 */
public interface PayloadCodec {

    /** 编码标识，写入 iot_message_log.codec，便于事后按原始格式重放。 */
    String name();

    byte[] encode(Envelope envelope);

    /**
     * 解码报文。
     *
     * @throws MalformedPayloadException 解析失败（调用方必须落 raw_payload，不得丢原始字节）
     */
    Envelope decode(byte[] payload);

    /** 解析异常：携带原始字节长度而不携带内容，避免敏感载荷进日志。 */
    class MalformedPayloadException extends RuntimeException {

        private final int payloadLength;

        public MalformedPayloadException(String message, int payloadLength, Throwable cause) {
            super(message + "（长度 " + payloadLength + " 字节）", cause);
            this.payloadLength = payloadLength;
        }

        public int payloadLength() {
            return payloadLength;
        }
    }
}
