package com.lrs.buddy.modules.log.annotation;

import com.lrs.buddy.modules.log.enums.BusinessType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解。
 *
 * <p>只保留这一个日志切面是刻意的取舍：横切关注点的判断标准是
 * "每个业务模块都要、且与业务逻辑无关"。操作日志符合，
 * 而"某某模块的缓存清理""某某模块的耗时统计"只服务单个业务，
 * 写成切面反而把逻辑藏起来了，直接写在 Service 里更清楚。
 *
 * <p>用法：
 * <pre>{@code
 * @OperateLog(title = "用户管理", businessType = BusinessType.INSERT)
 * @PostMapping
 * public R<Void> save(...) { ... }
 * }</pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperateLog {

    /** 模块/功能标题 */
    String title() default "";

    /** 操作类型 */
    BusinessType businessType() default BusinessType.OTHER;

    /**
     * 是否记录请求参数。
     * 涉及密码、身份证等敏感信息的接口应设为 false，避免明文落库。
     */
    boolean logParam() default true;

    /** 是否记录返回结果 */
    boolean logResult() default true;
}
