package com.lrs.buddy.framework.modules.log.aspect;

import cn.hutool.extra.servlet.JakartaServletUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrs.buddy.framework.modules.log.annotation.OperateLog;
import com.lrs.buddy.framework.modules.log.model.OperateLogEvent;
import com.lrs.buddy.framework.modules.log.service.OperateLogService;
import com.lrs.buddy.framework.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 操作日志切面。
 *
 * <p>环绕通知而不是后置通知：只有环绕才能同时拿到"返回值"和"抛出的异常"，
 * 并且能统计真实耗时。
 *
 * <p>记录在 finally 中完成：无论成功失败都要留下痕迹，
 * 而且异常必须原样继续抛出，不能被切面吞掉。
 */
@Slf4j
@Aspect
@Component
@Order(200)
@RequiredArgsConstructor
public class OperateLogAspect {

    /** 参数与结果的截断长度，避免大对象把日志表撑爆 */
    private static final int MAX_TEXT_LENGTH = 2000;

    private final OperateLogService operateLogService;
    private final ObjectMapper objectMapper;

    @Around("@annotation(operateLog)")
    public Object around(ProceedingJoinPoint point, OperateLog operateLog) throws Throwable {
        long start = System.currentTimeMillis();
        Integer status = 0;
        String errorMsg = null;
        Object result = null;

        try {
            result = point.proceed();
            return result;
        } catch (Throwable e) {
            status = 1;
            // 只保留消息不保留堆栈：堆栈很长且通常已在业务日志里打过
            errorMsg = e.getMessage();
            throw e;
        } finally {
            try {
                record(point, operateLog, status, errorMsg, result, System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.warn("记录操作日志异常：{}", e.getMessage());
            }
        }
    }

    private void record(ProceedingJoinPoint point, OperateLog annotation,
                        Integer status, String errorMsg, Object result, long costTime) {
        HttpServletRequest request = currentRequest();

        String param = null;
        if (annotation.logParam()) {
            param = toJson(point.getArgs());
        }
        String jsonResult = null;
        if (annotation.logResult() && result != null) {
            jsonResult = toJson(result);
        }

        OperateLogEvent event = OperateLogEvent.builder()
                .title(annotation.title())
                .businessType(annotation.businessType().getCode())
                .method(point.getSignature().toShortString())
                .requestMethod(request == null ? null : request.getMethod())
                .operatorId(SecurityUtils.getUserId())
                .operatorName(SecurityUtils.getUsername())
                .operUrl(request == null ? null : request.getRequestURI())
                .operIp(request == null ? null : JakartaServletUtil.getClientIP(request))
                .operParam(param)
                .jsonResult(jsonResult)
                .status(status)
                .errorMsg(truncate(errorMsg))
                .costTime(costTime)
                .operTime(LocalDateTime.now())
                .build();

        operateLogService.saveAsync(event);
    }

    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return truncate(objectMapper.writeValueAsString(obj));
        } catch (Exception e) {
            // 某些对象无法序列化（如 MultipartFile），忽略即可
            return null;
        }
    }

    /** 包可见以便单测锁定截断边界（曾因 substring+"..." 超长导致审计写库失败）。 */
    static String truncate(String text) {
        if (text == null) {
            return null;
        }
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        // 关键：为省略号预留 3 个字符，保证截断后总长恰为 MAX_TEXT_LENGTH，
        // 否则 substring(0,2000)+"..." 会得到 2003 字符，撑爆 VARCHAR(2000) 导致审计写库失败。
        return text.substring(0, MAX_TEXT_LENGTH - 3) + "...";
    }
}
