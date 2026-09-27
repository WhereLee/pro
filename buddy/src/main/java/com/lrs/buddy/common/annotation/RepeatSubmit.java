package com.lrs.buddy.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 防重复提交。
 *
 * <p>解决的是幂等问题中最常见的场景：用户手抖连点两次"提交"、
 * 或者前端没做防抖导致同一个请求发了两遍，数据库里就多出两条一样的记录。
 *
 * <p>实现方式是"令牌式"：请求进来先在 Redis 占位，
 * 占位成功才执行业务，占位失败说明短时间内已提交过，直接拒绝。
 * 占位键带 TTL，到期自动释放，不需要手动清理。
 *
 * <p>注意它的能力边界：只防"短时间内的重复提交"，
 * 真正的分布式幂等（如支付回调）需要业务层面的唯一键或状态机，不能指望这个注解。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RepeatSubmit {

    /** 间隔时间（毫秒），在此期间内的重复请求会被拦截 */
    long interval() default 3000L;

    /** 被拦截时的提示语 */
    String message() default "";
}
