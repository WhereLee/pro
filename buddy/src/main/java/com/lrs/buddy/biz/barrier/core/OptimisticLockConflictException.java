package com.lrs.buddy.biz.barrier.core;

/**
 * 跨实例并发写冲突：store 以 {@code @Version} 乐观锁 CAS 落库时，发现目标状态行的版本已被其他实例改动
 * （或状态行被并发创建、撞唯一索引）。由 store 抛出；引擎据此决定让步（reconcile/alignOnStartup，下次心跳自愈）
 * 或有界重试（manual，重试耗尽则上抛，由 web 层翻译为友好冲突响应）。
 */
public class OptimisticLockConflictException extends RuntimeException {

    public OptimisticLockConflictException(String message) {
        super(message);
    }

    public OptimisticLockConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
