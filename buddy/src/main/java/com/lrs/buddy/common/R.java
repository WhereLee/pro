package com.lrs.buddy.common;

import java.io.Serializable;

/**
 * 统一响应体。
 *
 * <p>使用 Java 17 record：不可变、自带 equals/hashCode/toString，
 * 且 Jackson 2.12+ 原生支持 record 序列化，无需额外配置。
 *
 * <p>约定：所有 Controller 方法一律返回 {@code R<T>}，
 * 不在 Controller 内 try-catch 后手工组装错误响应——那是全局异常处理器的职责。
 *
 * @param code     业务码，见 {@link ResultCode}
 * @param message  提示信息，可直接展示给用户
 * @param data     业务数据
 * @param timestamp 服务端时间戳，便于前端排查"请求何时发出"
 */
public record R<T>(int code, String message, T data, long timestamp) implements Serializable {

    private static final int SUCCESS_CODE = ResultCode.SUCCESS.getCode();

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(SUCCESS_CODE, ResultCode.SUCCESS.getMessage(), data, System.currentTimeMillis());
    }

    public static <T> R<T> ok(T data, String message) {
        return new R<>(SUCCESS_CODE, message, data, System.currentTimeMillis());
    }

    public static <T> R<T> fail(String message) {
        return fail(ResultCode.ERROR.getCode(), message);
    }

    public static <T> R<T> fail(ResultCode resultCode) {
        return new R<>(resultCode.getCode(), resultCode.getMessage(), null, System.currentTimeMillis());
    }

    public static <T> R<T> fail(ResultCode resultCode, String message) {
        return new R<>(resultCode.getCode(), message, null, System.currentTimeMillis());
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null, System.currentTimeMillis());
    }

    public boolean isSuccess() {
        return this.code == SUCCESS_CODE;
    }
}
