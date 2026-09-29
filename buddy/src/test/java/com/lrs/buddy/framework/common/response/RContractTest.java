package com.lrs.buddy.framework.common.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一响应 R 与业务码 ResultCode 的契约单测：这是"双层契约"里 body.code 的源头。
 */
class RContractTest {

    @Test
    @DisplayName("R.ok：code=200、success=true、message 为成功文案")
    void ok() {
        R<String> r = R.ok("payload");
        assertEquals(200, r.code());
        assertTrue(r.isSuccess());
        assertEquals("payload", r.data());
        assertTrue(r.timestamp() > 0);
    }

    @Test
    @DisplayName("R.ok 带自定义 message")
    void okWithMessage() {
        R<Void> r = R.ok(null, "登录成功");
        assertEquals("登录成功", r.message());
        assertNull(r.data());
    }

    @Test
    @DisplayName("R.fail(ResultCode) 用枚举码与默认文案")
    void failWithResultCode() {
        R<Void> r = R.fail(ResultCode.REPEAT_SUBMIT);
        assertEquals(1404, r.code());
        assertEquals("操作过于频繁，请勿重复提交", r.message());
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("R.fail(code,msg) 保留业务码，success 由 code 推导")
    void failCustom() {
        R<Void> r = R.fail(500, "用户名或密码错误");
        assertEquals(500, r.code());
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("鉴权类业务码集中在 1xxx，与 HTTP 401 语义可区分")
    void authCodes() {
        assertEquals(1401, ResultCode.TOKEN_EXPIRED.getCode());
        assertEquals(1402, ResultCode.TOKEN_INVALID.getCode());
        assertEquals(1403, ResultCode.FORCED_OFFLINE.getCode());
        assertEquals(1404, ResultCode.REPEAT_SUBMIT.getCode());
    }
}
