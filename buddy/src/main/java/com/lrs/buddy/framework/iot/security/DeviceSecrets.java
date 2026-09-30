package com.lrs.buddy.framework.iot.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrs.buddy.framework.common.util.CryptoUtil;
import com.lrs.buddy.framework.iot.envelope.Envelope;
import com.lrs.buddy.framework.iot.envelope.JsonPayloadCodec;

import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 设备密钥派生与报文签名（swap-protocol.md §3.1、§9）。
 *
 * 三处刻意的取舍：
 * 1 连接口令密钥与报文签名密钥由同一主密钥派生而非复用 —— 一处泄露不等于两处沦陷；
 * 2 签名覆盖 cmd/msgId/issuedAt/expireAt 加 data 摘要 —— 只签 data 会被"改 cmd 不改 data"绕过；
 * 3 比较一律常量时间 —— 否则签名校验可被计时逐字节猜。
 */
public class DeviceSecrets {

    public static final String PURPOSE_CONN = "conn";
    public static final String PURPOSE_MSG = "msg";

    private final JsonPayloadCodec codec;

    public DeviceSecrets(JsonPayloadCodec codec) {
        this.codec = codec;
    }

    public String deriveConnSecret(String masterSecret) {
        return CryptoUtil.derive(masterSecret, PURPOSE_CONN);
    }

    public String deriveMsgSecret(String masterSecret) {
        return CryptoUtil.derive(masterSecret, PURPOSE_MSG);
    }

    /**
     * 连接口令：password = HMAC(connSecret, username)，username = deviceId|ts|nonce。
     * 口令本身不含密钥材料，抓包无法反推主密钥。
     */
    public String connectionPassword(String masterSecret, String username) {
        return CryptoUtil.hmacSha256Hex(deriveConnSecret(masterSecret), username);
    }

    public String sign(String msgSecret, Envelope envelope) {
        return CryptoUtil.hmacSha256Hex(msgSecret, signBase(envelope));
    }

    public boolean verify(String msgSecret, Envelope envelope) {
        if (envelope.sign() == null || envelope.sign().isBlank()) {
            return false;
        }
        return CryptoUtil.equalsConstantTime(sign(msgSecret, envelope), envelope.sign());
    }

    /** 签名基串：缺失字段以空串参与，避免"省略字段"绕过校验；跨端一致性测试直接调用。 */
    public String signBase(Envelope envelope) {
        String digest = sha256Hex(codec.canonicalData(envelope.data()));
        return nullToEmpty(envelope.cmd()) + "\n"
                + nullToEmpty(envelope.msgId()) + "\n"
                + (envelope.issuedAt() == null ? "" : envelope.issuedAt()) + "\n"
                + (envelope.expireAt() == null ? "" : envelope.expireAt()) + "\n"
                + nullToEmpty(envelope.nonce()) + "\n"
                + digest;
    }

    private static String sha256Hex(byte[] data) {
        if (data == null) {
            return "";
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 载荷里的字符串取值（事件类型等），缺失返回 null。 */
    public static String dataText(JsonNode data, String field) {
        if (data == null || !data.isObject()) {
            return null;
        }
        JsonNode node = data.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
