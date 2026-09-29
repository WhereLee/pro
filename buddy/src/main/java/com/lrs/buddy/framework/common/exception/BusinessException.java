package com.lrs.buddy.framework.common.exception;

import com.lrs.buddy.framework.common.response.ResultCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>与系统异常的区别：业务异常是"预期内的失败"（如"用户名已存在"），
 * 消息可安全展示给前端；系统异常是"预期外的错误"，统一返回兜底文案，
 * 详细堆栈只写日志，不外泄。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.ERROR.getCode();
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
