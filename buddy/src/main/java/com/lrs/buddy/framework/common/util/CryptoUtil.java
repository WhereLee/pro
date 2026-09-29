package com.lrs.buddy.framework.common.util;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 加解密与摘要工具：AES-GCM 加密敏感值、HMAC 做索引哈希与报文签名、常量时间比较。
 *
 * <p>为什么密文与哈希两列并存（swap-ddl.md §9）：密文带随机 IV，**不可比较**，只能用于业务读取；
 * 唯一约束与等值查询必须走确定性哈希（HMAC-SHA256(pepper, plain)）。
 * 若把唯一索引建在密文列上，同一手机号能注册两次——而且看起来一切正常。
 *
 * <p>异常信息一律不回显明文：解密失败只报"密文非法或主密钥不匹配"。
 */
public final class CryptoUtil {

    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoUtil() {
    }

    /** AES-GCM 加密，输出 base64(iv ‖ ciphertext‖tag)。主密钥为任意长度字符串，经 SHA-256 推导。 */
    public static String aesGcmEncrypt(String masterSecret, String plaintext) {
        byte[] key = decodeKey(masterSecret);
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 加密失败", e);
        }
    }

    public static String aesGcmDecrypt(String masterSecret, String cipherText) {
        byte[] key = decodeKey(masterSecret);
        byte[] blob = Base64.getDecoder().decode(cipherText);
        if (blob.length <= GCM_IV_BYTES) {
            throw new IllegalArgumentException("密文长度非法");
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES));
            byte[] plain = cipher.doFinal(blob, GCM_IV_BYTES, blob.length - GCM_IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("密文非法或主密钥不匹配", e);
        }
    }

    /** HMAC-SHA256 十六进制：用于确定性哈希索引与报文签名。 */
    public static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    public static String sha256Hex(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    /** 密钥派生：从一个主密钥派生用途隔离的子密钥，避免一处泄露两处沦陷。 */
    public static String derive(String masterSecret, String purpose) {
        return hmacSha256Hex(masterSecret, purpose);
    }

    /** 常量时间比较：防签名/口令校验被计时攻击逐字节猜。 */
    public static boolean equalsConstantTime(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从口令推导 AES 密钥（SHA-256，固定 32 字节）。
     *
     * 不用 base64 存密钥是因为长度写错（16/24/32 以外）只会在第一次加解密时爆，
     * 而在启动阶段就校验失败比运行中解密失败便宜得多；
     * 推导方式让“口令长度”不再是一个隐藏约束，且仍然可以接受任意长度的强口令。
     */
    private static byte[] decodeKey(String masterSecret) {
        if (masterSecret == null || masterSecret.isBlank()) {
            throw new IllegalArgumentException("主密钥未配置");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(masterSecret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("主密钥推导失败", e);
        }
    }
}
