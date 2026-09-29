package com.lrs.buddy.framework.common.exception;

import com.lrs.buddy.framework.common.response.R;
import com.lrs.buddy.framework.common.response.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>存在的意义：让业务代码只管抛异常，不用在每个 Controller 里写
 * {@code try-catch return R.fail(...)}。原写法有两个坏处——
 * 一是异常被吞掉导致排查时看不到堆栈，二是响应格式容易写得不一致。
 *
 * <p>注意 Spring Security 的 {@code AuthenticationEntryPoint} 与
 * {@code AccessDeniedHandler} 另在过滤器链中配置，因为鉴权失败发生在
 * 进入 DispatcherServlet 之前，本类捕获不到；这里处理的是通过了鉴权、
 * 在 Controller 内部（如方法级 {@code @PreAuthorize}）抛出的同类异常。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：预期内的失败，只记 warn，不打堆栈 */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常：{}", e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /** @RequestBody 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        return R.fail(ResultCode.BAD_REQUEST.getCode(), message);
    }

    /** 表单绑定（@ModelAttribute）参数校验失败 */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        return R.fail(ResultCode.BAD_REQUEST.getCode(), message);
    }

    /** Spring 6.1+ 对 @RequestParam/@PathVariable 的校验失败走这里 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public R<Void> handleHandlerMethodValidation(HandlerMethodValidationException e) {
        String message = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(org.springframework.context.MessageSourceResolvable::getDefaultMessage)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("；"));
        return R.fail(ResultCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingParameter(MissingServletRequestParameterException e) {
        return R.fail(ResultCode.BAD_REQUEST.getCode(), "缺少必需参数：" + e.getParameterName());
    }

    /** 请求体 JSON 格式错误或无法反序列化 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return R.fail(ResultCode.BAD_REQUEST.getCode(), "请求体格式错误");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return R.fail(ResultCode.BAD_REQUEST.getCode(), "不支持的请求方法：" + e.getMethod());
    }

    /** 已登录但权限不足（方法级鉴权） */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> handleAccessDenied(AccessDeniedException e) {
        return R.fail(ResultCode.FORBIDDEN);
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleAuthentication(AuthenticationException e) {
        return R.fail(ResultCode.UNAUTHORIZED);
    }

    /**
     * 兜底：系统异常。
     *
     * <p>关键取舍——对外只返回笼统文案并附带请求路径，详细堆栈只进日志。
     * 直接把 e.getMessage() 抛给前端，可能泄露表名、SQL、内网地址等信息。
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常，uri={}，method={}", request.getRequestURI(), request.getMethod(), e);
        return R.fail(ResultCode.ERROR.getCode(), "服务器开小差了，请稍后重试");
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + "：" + fieldError.getDefaultMessage();
    }
}
