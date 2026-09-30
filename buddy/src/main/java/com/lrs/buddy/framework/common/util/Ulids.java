package com.lrs.buddy.framework.common.util;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * ULID 生成（26 位 Crockford base32：48bit 毫秒时间 + 80bit 随机，共 128bit）。
 *
 * 为什么不用 UUID：msgId 与单号要进索引和日志。UUID 36 字符且无序；
 * ULID 前缀按时间单调，事后按 msgId 排序/切片都不必回表取时间。
 *
 * 位取法刻意写得直白（逐位取），而不是用移位累加：
 * 累加写法容易在 5bit 对齐上多读一个字节，产生"只在特定随机序列下才越界"的偶发异常，
 * 这类 bug 在测试里表现为偶发失败、线上表现为偶发 500。
 */
public final class Ulids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulids() {
    }

    public static String next() {
        return fromMillis(Instant.now().toEpochMilli());
    }

    static String fromMillis(long millis) {
        byte[] bytes = new byte[16];
        for (int i = 5; i >= 0; i--) {
            bytes[5 - i] = (byte) (millis & 0xFF);
            millis >>>= 8;
        }
        RANDOM.nextBytes(new byte[0]);
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        System.arraycopy(random, 0, bytes, 6, 10);

        char[] chars = new char[26];
        for (int c = 0; c < 26; c++) {
            int value = 0;
            for (int b = 0; b < 5; b++) {
                int bitIndex = c * 5 + b;
                int bit = bitIndex < 128 ? bitAt(bytes, bitIndex) : 0;
                value = (value << 1) | bit;
            }
            chars[c] = ALPHABET[value];
        }
        return new String(chars);
    }

    /** 大端位序：bitIndex 0 是最高位。前 2 位补零，故首字符不超过 '7'。 */
    private static int bitAt(byte[] bytes, int bitIndex) {
        int byteIndex = bitIndex >>> 3;
        int bitOffset = 7 - (bitIndex & 7);
        return (bytes[byteIndex] >> bitOffset) & 1;
    }

    public static boolean isUlid(String candidate) {
        if (candidate == null || candidate.length() != 26) {
            return false;
        }
        String alphabet = new String(ALPHABET);
        for (char c : candidate.toCharArray()) {
            if (alphabet.indexOf(Character.toUpperCase(c)) < 0) {
                return false;
            }
        }
        return true;
    }
}
