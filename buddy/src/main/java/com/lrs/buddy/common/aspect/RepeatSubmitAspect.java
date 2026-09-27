package com.lrs.buddy.common.aspect;

import com.lrs.buddy.common.BusinessException;
import com.lrs.buddy.common.ResultCode;
import com.lrs.buddy.common.annotation.RepeatSubmit;
import com.lrs.buddy.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 防重复提交切面。
 *
 * <p>限流键的组成：用户 + 接口 + 参数指纹。
 * 三个维度缺一不可——
 * 只看接口会让 A 用户提交后 B 用户被误拦；
 * 不看参数则同一接口的不同数据提交会互相干扰。
 */
@Slf4j
@Aspect
@Component
@Order(100)
@RequiredArgsConstructor
public class RepeatSubmitAspect {

    private static final String REPEAT_KEY_PREFIX = "buddy:repeat:";

    private final RedisTemplate<String, Object> redisTemplate;

    @Around("@annotation(repeatSubmit)")
    public Object around(ProceedingJoinPoint point, RepeatSubmit repeatSubmit) throws Throwable {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            // 非 Web 环境（如定时任务内部调用）无法判断重复，直接放行
            return point.proceed();
        }

        String key = buildKey(request, point.getArgs());
        boolean acquired;
        try {
            Boolean result = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofMillis(repeatSubmit.interval()));
            acquired = Boolean.TRUE.equals(result);
        } catch (Exception e) {
            // Redis 不可用时选择放行：幂等防护是"锦上添花"，
            // 不能因为中间件故障就让整个新增功能不可用
            log.warn("防重复提交检查失败（Redis 不可用？），本次放行：{}", e.getMessage());
            return point.proceed();
        }

        if (!acquired) {
            String message = repeatSubmit.message();
            if (message == null || message.isBlank()) {
                message = ResultCode.REPEAT_SUBMIT.getMessage();
            }
            throw new BusinessException(ResultCode.REPEAT_SUBMIT, message);
        }

        return point.proceed();
    }

    private String buildKey(HttpServletRequest request, Object[] args) {
        Long userId = SecurityUtils.getUserId();
        String uri = request.getRequestURI();
        String paramFingerprint = md5(args == null ? "" : String.valueOf(argsHash(args)));
        return REPEAT_KEY_PREFIX + (userId == null ? "anonymous" : userId) + ":" + uri + ":" + paramFingerprint;
    }

    /**
     * 参数指纹。
     *
     * <p>用哈希而不是原始参数：参数可能很长（含富文本），
     * 直接拼进 Redis key 会让 key 变得巨大且可能超过长度限制。
     */
    private static String argsHash(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (Object arg : args) {
            if (arg == null) {
                sb.append("null|");
            } else if (arg instanceof jakarta.servlet.http.HttpServletRequest) {
                // 请求对象本身不参与指纹
                continue;
            } else {
                sb.append(arg.toString()).append("|");
            }
        }
        return sb.toString();
    }

    private static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // MD5 必定可用，走到这里说明环境异常，退化为长度+hashCode
            return String.valueOf(text.hashCode());
        }
    }

    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }
}
